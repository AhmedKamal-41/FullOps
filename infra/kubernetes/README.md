# Kubernetes deployment (local kind)

A Kustomize-based deployment of the four FulfillOps services, designed to run on a local
[kind](https://kind.sigs.k8s.io/) cluster. It is a portfolio-grade packaging exercise, not
a production cluster definition.

## Layout

```
infra/kubernetes/
  base/                     # environment-independent manifests
    namespace.yaml
    configmap.yaml          # non-secret config: infra addresses, database names
    secret.example.yaml     # TEMPLATE for the required Secret — no real values, not built
    order-service.yaml      # Deployment + Service (one file per service)
    inventory-service.yaml
    payment-service.yaml
    fulfillment-service.yaml
    pod-disruption-budgets.yaml
    network-policies.yaml
    kustomization.yaml
  overlays/
    kind/                   # local overlay: pins images to the :local tag
      kustomization.yaml
  kind-cluster.yaml         # kind cluster definition
```

Build either with:

```
kubectl kustomize infra/kubernetes/base
kubectl kustomize infra/kubernetes/overlays/kind
```

## One-command local run

`make kind-up` (or `scripts/kind-deploy.sh`) creates the cluster, builds and loads the
images, deploys, waits for every rollout, smoke-tests readiness, and tears the cluster
down again. It only ever creates and deletes a cluster named `fulfillops`, and never
deletes a Docker volume. Run `make infra-up` first — the services need the infrastructure.

## Deliberate trade-offs

**Stateful infrastructure stays outside the cluster.** PostgreSQL, Kafka, Redis, and
Keycloak are not deployed as pods. They remain in the Docker Compose stack on the host,
and the pods reach them at `host.docker.internal` (see `base/configmap.yaml`). Running
production-grade stateful systems in-cluster (operators, StatefulSets, persistent volumes,
backups) is a large undertaking that adds no signal to what this project is demonstrating —
that the *services* are packaged, probed, resource-bounded, and policy-guarded correctly.
Swapping the ConfigMap/Secret addresses for managed endpoints (RDS, MSK, ElastiCache) is
all that changes for a real environment.

**NetworkPolicies are not enforced by kind's default CNI.** kind ships with kindnet, which
does not implement NetworkPolicy, so on a plain kind cluster `base/network-policies.yaml`
documents intent but does not block traffic. The same manifests are enforced on a cluster
running Calico or Cilium. They are included so the intended default-deny posture travels
with the deployment.

**Secrets are a template only.** `secret.example.yaml` holds placeholder values and is not
part of the Kustomize build, so `kubectl kustomize` never emits a Secret full of
placeholders. The deploy script creates the real Secret from the repo `.env` (the same
fictional local-only passwords Compose uses). For any real environment, use a secrets
manager (Sealed Secrets, External Secrets, SSM/Secrets Manager) rather than a plain file.

## Hardening applied to every service

- Runs as a non-root user (from the image) with `runAsNonRoot`, `allowPrivilegeEscalation:
  false`, all Linux capabilities dropped, a read-only root filesystem, and the
  `RuntimeDefault` seccomp profile.
- Liveness, readiness, and startup probes against Spring Boot's dedicated probe endpoints.
- CPU/memory requests and limits, with the JVM told to respect the memory limit.
- Two replicas plus a PodDisruptionBudget, so a node drain or rollout never takes the last
  replica.
