# Known limitations

FulfillOps is a portfolio project that demonstrates real distributed-systems and operations
engineering. It is deliberately honest about where the boundaries are. Nothing below is a
hidden gap — each is a conscious scope decision.

## Correctness and semantics

- **Delivery is at least once, not exactly once.** Kafka redelivers; correctness comes from
  idempotent consumers (inbox keyed by `(event_id, consumer_name)`) and database constraints.
  The project never claims exactly-once processing. See [ADR 0004](adr/0004-at-least-once-delivery.md).
- **Payment is a deterministic simulator.** There is no real payment gateway. Outcomes are
  driven by seeded, documented "magic" amounts (the convention real card-processor sandboxes
  use). No card number, bank detail, or SSN is ever accepted, logged, or stored. See
  [`docs/SECURITY.md`](SECURITY.md) and [ADR 0010](adr/0010-payment-simulator-resilience.md).
- **Only event schema `v1` exists**, so a real cross-version compatibility test (does a future
  `v2` still accept everything `v1` did) is not yet written. Stated in
  [`contracts/README.md`](../contracts/README.md).
- **Reconciliation and SLA thresholds are demo defaults**, labeled as such in configuration,
  not tuned production values.

## Product surface

- The console covers six operator routes. **Refunds and inventory adjustments are ADMIN HTTP
  commands only** — they have no console screen yet (planned).
- There is no customer-facing web UI; customers are represented by the Order API and demo
  scripts.

## Deployment and infrastructure

- **The services are not deployed to any cloud.** Local run is the host (`make run-*`) or the
  production-like Compose profile (`make demo-up`).
- **Kubernetes and Terraform are packaging references, not running environments.** The
  Kustomize manifests are validated (`kubectl kustomize`) and deploy to local kind; the AWS
  Terraform is validated in CI but **never applied** and provisions nothing. See
  [`infra/kubernetes/README.md`](../infra/kubernetes/README.md) and
  [`infra/terraform/README.md`](../infra/terraform/README.md).
- kind's default CNI (kindnet) does not enforce NetworkPolicy, so the included NetworkPolicies
  are enforced only on a policy-capable CNI (Calico/Cilium).
- Local Kafka and Redis are single-node. Postgres is one instance with a database per service.

## Verification notes

- **The coverage gate is wired but its true figure is unmeasured.** It is set to a conservative
  0.60 floor pending a stable combined unit/integration baseline. See
  [`docs/TESTING.md`](TESTING.md).
- **k6 latency numbers are sandbox figures, not capacity claims** — see
  [`docs/TESTING.md`](TESTING.md#performance-k6--measured-with-limits-stated).
- **Grafana, distributed-trace, and failure-recovery screenshots are not captured in this
  repository.** The console screenshots (`docs/screenshots/`) are captured from the console's
  demo mode. Capture instructions for Grafana, Tempo, and recovery evidence are in
  [`docs/demo/FAILURE_DEMO.md`](demo/FAILURE_DEMO.md).
