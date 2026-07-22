# Resume evidence

This maps proposed resume bullets to the exact code, tests, and generated evidence that backs
them, so every claim is defensible in an interview.

**Read this first.** The bullets below are **templates**. Any bullet that states a number
(throughput, latency, coverage, test count) has that number shown as `‹MEASURE›` — you must
replace it with a value that a command in this repository actually produced on a machine you can
describe, per the project's rule against unproven claims. The counts that *are* filled in (176
unit tests) were produced this phase with `./mvnw -B test`; the rest are pending a CI run. Do not
use `production-grade`, `bank-grade`, `exactly-once`, `zero-downtime`, or scale claims — none is
supportable here.

## Java / backend engineer bullets

### 1. Event-driven microservices with reliable messaging
> Designed and built four independently deployable Java 21 / Spring Boot services that
> coordinate through versioned Kafka events with a transactional outbox and an idempotent inbox
> — no shared database — making at-least-once delivery correct via inbox de-duplication keyed on
> `(event_id, consumer_name)` and database constraints rather than any exactly-once claim.

| Evidence | Where |
| --- | --- |
| Code | `services/*/src/.../messaging/` (`OutboxRelay`, `*Listener`), `contracts/events/` |
| Tests | outbox/inbox integration tests (`OutboxRelayIT`, duplicate-delivery no-op ITs) |
| Design | [ADR 0002](adr/0002-choreography-not-orchestration.md), [0003](adr/0003-outbox-inbox.md), [0004](adr/0004-at-least-once-delivery.md); [`docs/EVENT_CATALOG.md`](EVENT_CATALOG.md) |

### 2. Concurrency-safe inventory under real contention
> Implemented race-safe stock reservation with `SELECT ... FOR UPDATE` plus optimistic locking,
> proven by an integration test that races 10 concurrent orders for 5 units of one SKU and
> asserts exactly 5 succeed with stock never going negative.

| Evidence | Where |
| --- | --- |
| Code | `services/inventory-service/.../service/ReservationTransaction.java`, `StockLevelRepository.findBySkuForUpdate` |
| Tests | `ReservationConcurrencyIT`, `ReservationIT` (DB rejects negative stock even bypassing the app) |
| Design | [ADR 0006](adr/0006-postgresql-per-service.md) |

### 3. Compensating saga and automated recovery
> Built a choreographed compensation saga (release inventory, refund payment, cancel
> fulfillment) plus a reconciliation job guarded by a Postgres advisory lock that finds stuck
> orders and either safely retries or escalates them to a reviewable incident.

| Evidence | Where |
| --- | --- |
| Code | `OrderCancellationTransaction`, `ReconciliationService`, per-service compensation listeners |
| Tests | `CancellationSagaIT`, `ReconciliationServiceIT`, per-service `CancellationCompensationIT` |
| Design | [ADR 0002](adr/0002-choreography-not-orchestration.md); [`docs/DOMAIN_MODEL.md`](DOMAIN_MODEL.md) |

## Operations-analyst bullets

### 4. Operations read model and KPI surface
> Built an operations read model and API — KPI overview/time-series/stage-duration, an
> SLA-breach-aware backlog and stuck-orders view, a filterable CSV-exportable work queue, and
> per-order event timelines — with every KPI formula documented exactly, never a fabricated
> number.

| Evidence | Where |
| --- | --- |
| Code | `services/order-service/.../ops/**`, `/api/v1/ops/**` |
| Definitions | [`docs/KPI_DICTIONARY.md`](KPI_DICTIONARY.md) |
| Design | [ADR 0008](adr/0008-ops-projection-ownership.md) |

### 5. Operator console and incident management
> Delivered a React/TypeScript operator console (six routes) with PKCE authentication and a full
> incident acknowledge → assign → resolve lifecycle, turning raw exceptions into a workable
> queue for a fulfillment team.

| Evidence | Where |
| --- | --- |
| Code | `apps/ops-console/`, incident API in `order-service` |
| Screenshots | [`docs/screenshots/`](screenshots/) (real, from demo mode) |
| Process | [`docs/runbooks/INCIDENT_MANAGEMENT.md`](runbooks/INCIDENT_MANAGEMENT.md) |

### 6. Observability and failure demonstration
> Instrumented the platform with Prometheus metrics and OpenTelemetry tracing (one order follows
> as a single distributed trace across all four services and every Kafka boundary), Grafana
> dashboards, and six committed failure-scenario scripts that visibly trigger and recover from
> known incidents.

| Evidence | Where |
| --- | --- |
| Code/config | `infra/compose/observability/`, per-service metrics |
| Demos | [`tests/failure-scenarios/`](../tests/failure-scenarios/), [`docs/demo/FAILURE_DEMO.md`](demo/FAILURE_DEMO.md) |
| Load tests | [`tests/perf/`](../tests/perf/), summaries in [`docs/evidence/k6/`](evidence/k6/) |

## Numbers you may quote (fill in from a real run)

| Claim | Source command | Status |
| --- | --- | --- |
| "176 unit tests, 0 failures" | `./mvnw -B test` | Measured this phase ✓ |
| Full test count (unit + integration) | `./mvnw -B verify` | `‹MEASURE›` — needs a Docker-capable run / CI |
| Business-code line coverage | `./mvnw -B verify` (JaCoCo) | `‹MEASURE›` — gate floor is 0.60; real figure pending CI |
| Order-submission throughput / p95 | `tests/perf/order-submission.js` | `‹MEASURE›` — sandbox p95 was 889 ms at 0% errors; re-run on a described machine |
| CI status (all jobs green) | GitHub Actions | `‹MEASURE›` — not run yet; nothing pushed |
