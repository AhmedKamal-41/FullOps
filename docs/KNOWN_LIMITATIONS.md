# Known limitations

FulfillOps is deliberately honest about where its boundaries are. Nothing below is a hidden gap —
each is a conscious scope decision or an environment constraint.

## Correctness and semantics

- **Delivery is at least once, not exactly once.** Kafka redelivers; correctness comes from
  idempotent consumers (an inbox keyed by `(event_id, consumer_name)`) and database constraints. The
  project never claims exactly-once processing. See
  [ARCHITECTURE.md](ARCHITECTURE.md#at-least-once-delivery-and-idempotency).
- **Payment is a deterministic simulator.** There is no real payment gateway. Outcomes are driven by
  seeded, documented "magic" amounts (the convention real card-processor sandboxes use). No card
  number, bank detail, or SSN is ever accepted, logged, or stored. See
  [`../SECURITY.md`](../SECURITY.md).
- **Only event schema `v1` exists**, so a real cross-version compatibility test (does a future `v2`
  still accept everything `v1` did) is not yet written. Stated in
  [`../contracts/README.md`](../contracts/README.md).
- **Reconciliation and SLA thresholds are demo defaults**, labeled as such in configuration, not
  tuned production values.

## Product surface

- The console covers six operator routes. **Refunds and inventory adjustments are ADMIN HTTP commands
  only** — they have no console screen yet.
- There is no customer-facing web UI; customers are represented by the Order API and demo scripts.

## Deployment and infrastructure

- **The services are not deployed to any cloud.** Local run is the host (`make run-*`) or the
  containerized Compose profile (`make demo-up`).
- **Kubernetes and Terraform are packaging references, not running environments.** The Kustomize
  manifests are validated (`kubectl kustomize`) and deploy to local kind; the AWS Terraform is
  validated but **never applied** and provisions nothing. See [`../infra/README.md`](../infra/README.md).
- kind's default CNI (kindnet) does not enforce NetworkPolicy, so the included NetworkPolicies are
  enforced only on a policy-capable CNI (Calico/Cilium).
- Local Kafka and Redis are single-node. Postgres is one instance with a database per service.

## Evidence and observability

- **The coverage gate is a conservative floor, not a measured figure.** Most coverage here comes from
  the Testcontainers integration suite, so a meaningful number requires running `./mvnw -B verify`
  with Docker available. See [`TESTING.md`](TESTING.md).
- **k6 latency numbers are sandbox figures, not capacity claims** — see
  [`TESTING.md`](TESTING.md#performance-k6--measured-with-limits-stated).
- **Grafana, distributed-trace, and failure-recovery screenshots are not committed.** Capturing them
  requires running the full observability stack; the console screenshots (`screenshots/`) are real,
  captured from the console's demo mode. Capture instructions for the rest are in [`DEMO.md`](DEMO.md).
