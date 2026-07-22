# Infrastructure

Deployment packaging for FulfillOps. The core system runs locally with Docker Compose (see the root
README); the artifacts here are two further packaging exercises:

- **`kubernetes/`** — a Kustomize deployment of the four services to a local
  [kind](https://kind.sigs.k8s.io/) cluster.
- **`terraform/`** — an AWS reference architecture that is validated but **never applied**.
- **`compose/`** — the Docker Compose stack (infrastructure, observability, and a containerized demo
  overlay) the rest of the project uses.

Neither the Kubernetes nor the Terraform artifact has been deployed to a real cloud environment.

## Kubernetes (local kind)

A Kustomize-based deployment of the four services, designed to run on a local kind cluster.

```
infra/kubernetes/
  base/                     # environment-independent manifests
    namespace.yaml
    configmap.yaml          # non-secret config: infra addresses, database names
    secret.example.yaml     # TEMPLATE for the required Secret — no real values, not built
    order-service.yaml      # Deployment + Service, one file per service
    inventory-service.yaml
    payment-service.yaml
    fulfillment-service.yaml
    pod-disruption-budgets.yaml
    network-policies.yaml
    kustomization.yaml
  overlays/
    kind/                   # local overlay: pins images to the :local tag
  kind-cluster.yaml         # kind cluster definition
```

Build the manifests with:

```
kubectl kustomize infra/kubernetes/base
kubectl kustomize infra/kubernetes/overlays/kind
```

One-command local run: `make kind-up` (or `scripts/kind-deploy.sh`) creates the cluster, builds and
loads the images, deploys, waits for every rollout, smoke-tests readiness, and can tear the cluster
down again. It only ever creates and deletes a cluster named `fulfillops`, and never deletes a Docker
volume. Run `make infra-up` first — the services need the infrastructure.

**Hardening applied to every service:** runs as a non-root user with `runAsNonRoot`,
`allowPrivilegeEscalation: false`, all Linux capabilities dropped, a read-only root filesystem, and
the `RuntimeDefault` seccomp profile; liveness/readiness/startup probes against Spring Boot's probe
endpoints; CPU/memory requests and limits with the JVM told to respect the memory limit; two replicas
plus a PodDisruptionBudget so a node drain never takes the last replica.

**Deliberate trade-offs:**

- **Stateful infrastructure stays outside the cluster.** PostgreSQL, Kafka, Redis, and Keycloak are
  not deployed as pods — they remain in the Compose stack on the host, and the pods reach them at
  `host.docker.internal`. Running production-grade stateful systems in-cluster adds no signal to what
  this packaging demonstrates: that the *services* are probed, resource-bounded, and policy-guarded
  correctly. Swapping the ConfigMap/Secret addresses for managed endpoints (RDS, MSK, ElastiCache) is
  all that changes for a real environment.
- **NetworkPolicies are not enforced by kind's default CNI.** kindnet does not implement
  NetworkPolicy, so on a plain kind cluster `base/network-policies.yaml` documents intent without
  blocking traffic. The same manifests are enforced on a Calico or Cilium cluster; they are included
  so the intended default-deny posture travels with the deployment.
- **Secrets are a template only.** `secret.example.yaml` holds placeholders and is excluded from the
  Kustomize build. The deploy script creates the real Secret from the repo `.env` (the same fictional
  local-only passwords Compose uses). A real environment should use a secrets manager.

## Terraform (AWS reference — never applied)

This directory is an **optional reference**. It has **not been applied** to any AWS account, and
nothing in this repository ever runs `terraform apply`. CI runs only `terraform fmt`,
`terraform validate`, `tflint`, and a Trivy IaC scan. No cloud resources are provisioned and no cost
is incurred.

**What it models:**

- `modules/network` — a VPC with private subnets across two AZs. Deliberately **no NAT gateway** (a
  real hourly + per-GB cost driver) and no internet gateway; only what a private database needs.
- `modules/database` — a single small RDS PostgreSQL instance (`db.t4g.micro`, gp3, encrypted,
  single-AZ). The master password is **managed by RDS and stored in Secrets Manager** — it never
  appears in a variable or in state. Each service keeps its own database on this shared instance,
  preserving the database-per-service boundary without paying for four instances.

**What it deliberately leaves out:** a compute tier (ECS Fargate behind an internal ALB), Amazon MSK
for Kafka, ElastiCache for Redis, and a managed OIDC provider. These are described but not coded,
because the cost-driving pieces (NAT gateways, MSK brokers, ALBs, multi-AZ RDS) are exactly what a
portfolio should not spin up by accident. The network and database modules show the pattern
(composable modules, RDS-managed secrets, private-only networking, tagging, remote state) without that
exposure.

**Local validation (no AWS account, no cost):**

```
terraform fmt -check -recursive
terraform init -backend=false
terraform validate
tflint --recursive
```

Remote state lives in S3 with native S3 locking; backend values are supplied at init time
(`cp backend.hcl.example backend.hcl`, then `terraform init -backend-config=backend.hcl`) so nothing
account-specific is committed. Before ever applying to a real environment, set
`deletion_protection = true` and `skip_final_snapshot = false`.

## Required commands

| Command | Purpose |
|---|---|
| `make infra-up` / `make infra-down` | Start / stop the Compose infrastructure (Postgres, Kafka, Redis, Keycloak) |
| `make demo-up` / `make demo-down` | Start / stop the full containerized demo (infra + observability + services) |
| `kubectl kustomize infra/kubernetes/base` | Render the Kubernetes manifests |
| `make kind-up` / `make kind-down` | Create / delete the local kind deployment |
| `terraform validate` (in `terraform/`) | Validate the AWS reference — never `apply` |
