# FulfillOps

**Event-driven order fulfillment and reliability platform.**

> **Status: Phase 11 complete — all four services are instrumented with Prometheus metrics and OpenTelemetry traces, with Grafana dashboards, Prometheus alert rules, six failure-scenario scripts, and k6 load tests on top of the operations console from Phase 10.** A single order can be followed as one real distributed trace across all four services and every Kafka boundary between them — verified directly against a running Tempo instance, not just asserted from the tracing config. The four services build, migrate their own databases, enforce JWT authentication against a real Keycloak realm, and publish/consume real Kafka events through a working transactional outbox and idempotent inbox, with JSON-Schema-validated contracts for every event. Order Service places orders idempotently (`POST /api/v1/orders`), consumes lifecycle events from all three other services to track an order end to end, and exposes cancellation (`POST /api/v1/orders/{orderId}/cancellation-requests`). Inventory Service reserves and releases stock with a row-locking concurrency strategy proven safe under real concurrent contention, and emits a low-stock signal event when a SKU crosses a configured threshold. Payment Service authorizes, declines, and refunds fictional payments with retry and circuit-breaker resilience, and reports how many transient failures preceded a final outcome. Fulfillment Service runs the warehouse workflow (`ASSIGNED → PICKING → PACKED → DISPATCHED → DELIVERED`) with operator HTTP controls and reacts to cancellation before dispatch. A failure anywhere — inventory rejection, a declined payment, a cancellation, a compensation that itself keeps failing — is compensated by choreography (no central saga database or orchestrator) and, when it cannot be safely auto-resolved, surfaces as a `REQUIRES_REVIEW` order plus an operations incident with a full acknowledge/assign/resolve lifecycle. Order Service maintains a rebuildable operations projection (`/api/v1/ops/**`, OPERATOR/ADMIN-only) exposing KPI overview/time-series/stage-duration reads, an SLA-breach-aware backlog and stuck-orders view, a searchable/filterable/CSV-exportable work queue, and per-order event timelines — every KPI formula is documented exactly in [`docs/KPI_DICTIONARY.md`](docs/KPI_DICTIONARY.md), never a fabricated number. The ops console (real Authorization Code + PKCE login against Keycloak, tokens never in `localStorage`) puts all of this in front of an operator across six routes — Overview, Work Queue, Order Detail, Incidents, Inventory Risk, Fulfillment Board — with a clearly labeled, network-failure-only demo-data fallback and no other path for mock data to reach the UI. Every service has a bounded-retry-then-dead-letter path per Kafka consumer and an ADMIN-only, audited replay endpoint for it; Order Service also runs a single-instance-guarded reconciliation job that finds stuck orders and safely nudges or escalates them. It never accepts, logs, or persists a card number, bank detail, or SSN. Every feature described below that isn't running code is explicitly labeled **(planned)**. See [`docs/PHASE_STATUS.md`](docs/PHASE_STATUS.md) for what is actually done and how it was verified.

## Product pitch

FulfillOps is a portfolio-grade simulation of how a real e-commerce fulfillment backend stays correct under failure. It follows an order from placement through inventory reservation, payment authorization, and warehouse dispatch — as four independently deployable services that coordinate through Kafka events instead of shared databases or synchronous call chains. When a step fails partway through, FulfillOps compensates: it releases reserved stock, refunds the simulated payment, and gives an operator a queue of exceptions to resolve, instead of silently losing or duplicating the order.

The project exists to demonstrate two things concretely, with working code and tests, not just diagrams:

1. **Java backend engineering** — service boundaries, transactional outboxes, idempotent consumers, concurrency-safe inventory updates, and recoverable failure handling.
2. **Operations systems thinking** — an operator console and analytics surface for a team that has to keep a fulfillment pipeline running, not just a set of REST endpoints.

## Target users

- **CUSTOMER** — places orders (`POST /api/v1/orders`), tracks their status end to end (`GET /api/v1/orders/{orderId}`, live through every real status change), and can request cancellation of their own order (`POST /api/v1/orders/{orderId}/cancellation-requests`). **(live)**
- **OPERATOR** — works the fulfillment queue, moves orders through `PICKING` → `PACKED` → `DISPATCHED` → `DELIVERED` (`fulfillment-service`'s operator HTTP endpoints), and can cancel a fulfillment before dispatch. **(live)** Works the exception queue through Order Service's ops API (`GET /api/v1/ops/work-queue`, `GET /api/v1/ops/backlog`, `GET /api/v1/ops/orders/{orderId}/timeline`) and the incident lifecycle (`GET /api/v1/ops/incidents`, acknowledge/assign/resolve). **(live)** Does all of this through `apps/ops-console` — Overview, Work Queue, Order Detail, Incidents, Inventory Risk, and Fulfillment Board — logged in through Keycloak. **(live)**
- **ADMIN** — everything an OPERATOR can do, plus refunds, inventory adjustments, the per-service dead-letter list/replay endpoints (`GET`/`POST /api/v1/admin/dead-letters/...`), and triggering an operations-projection rebuild (`POST /api/v1/admin/operations-projection/rebuild`). **(live)** The console's Incidents route includes an ADMIN-only dead-letter replay panel. **(live)** Inventory adjustments and refunds are still only reachable by hand-built HTTP calls; a console surface for them is *(planned)*.

## Core workflow

1. A customer submits an order using an idempotency key. **(live)**
2. Order Service validates the request and persists a `PENDING` order and an `OrderPlaced.v1` outbox event in the same transaction. **(live)**
3. Inventory Service reserves stock with a concurrency-safe update and emits `InventoryReserved.v1` or `InventoryRejected.v1`. **(live)**
4. Payment Service authorizes a fictional payment after reservation and emits `PaymentAuthorized.v1` or `PaymentDeclined.v1`. **(live)**
5. Fulfillment Service creates a fulfillment record after authorization and emits `FulfillmentAssigned.v1`. **(live)**
6. An operator advances the fulfillment through `PICKING`, `PACKED`, `DISPATCHED`, and `DELIVERED`. **(live)**
7. Order Service consumes lifecycle events from all three other services and exposes the customer's order view *and* a rebuildable, denormalized operations projection behind `/api/v1/ops/**` (KPI overview/time-series/stage-duration reads, SLA-breach-aware backlog, a searchable/CSV-exportable work queue, per-order timelines). **(live)**
8. Any failure triggers compensation — release inventory, refund the simulated payment, cancel fulfillment where allowed, or mark the order `REQUIRES_REVIEW` for an operator, who works it through the incident acknowledge/assign/resolve lifecycle. **(live)**
9. Retry topics, dead-letter topics, a reconciliation job, and an ADMIN-only replay endpoint keep failures visible instead of silent. **(live)**

See [`docs/DOMAIN_MODEL.md`](docs/DOMAIN_MODEL.md) for the full state machine and compensation rules.

## Architecture

Four independently deployable domain services, each owning its own PostgreSQL database, coordinating through versioned Kafka events with a transactional outbox/inbox pattern:

- **Order Service** — order lifecycle, idempotent order placement, cancellation saga orchestration, reconciliation, and the rebuildable operations projection/KPI/incident API.
- **Inventory Service** — stock levels, concurrency-safe reservation and release.
- **Payment Service** — deterministic fictional payment authorization, decline, and refund simulator.
- **Fulfillment Service** — warehouse workflow state machine and operator actions.
- **Ops Console** — React + TypeScript operations UI for order/exception management. *(planned)*

All four backend services are buildable, independently runnable Spring Boot 4.1.0 / Java 21 applications (`services/`), each with its own PostgreSQL database (migrated by its own Flyway history), native Spring Security OAuth2 Resource Server authentication against a real local Keycloak realm, and a real transactional outbox / idempotent inbox publishing to and consuming from real Kafka topics — with JSON-Schema-validated event contracts in [`contracts/events/`](contracts/events/). No service reads or writes another service's tables, and there is no shared JPA/domain-model module or central saga database — every cross-service reaction (reserve stock, authorize a payment, assign a fulfillment, release/refund/cancel on a compensation trigger) is choreographed: each service reacts to the events it cares about and emits its own in response.

Order Service has idempotent order placement (`POST /api/v1/orders`), a customer order view kept current by consuming lifecycle events from all three other services (`InventoryEventsListener`, `PaymentEventsListener`, `FulfillmentEventsListener`), and cancellation (`POST /api/v1/orders/{orderId}/cancellation-requests`) that tracks exactly which compensations a given order needs and finalizes only once every one of them is confirmed. Inventory Service reserves and releases stock with a `SELECT ... FOR UPDATE` concurrency strategy proven under real concurrent load (`ReservationConcurrencyIT` races 10 orders for 5 units of one SKU and confirms exactly 5 win, never negative stock), plus ADMIN product/adjustment endpoints and a Redis-backed availability cache that degrades to PostgreSQL on outage. Payment Service builds its own local order-context projection from `OrderPlaced.v1` (order id, customer id, currency, amount only — never card data), authorizes payments deterministically after `InventoryReserved.v1` using a documented, seeded, amount-driven simulator rule (the same "magic test amount" convention real card-processor sandboxes use), wraps the simulated provider call in a bounded retry and circuit breaker built on Resilience4j's framework-agnostic core libraries (see [ADR 0010](docs/adr/0010-payment-simulator-resilience.md)), and refunds automatically on a cancellation or a fulfillment cancellation, or on an idempotent OPERATOR/ADMIN command. Fulfillment Service runs the `ASSIGNED → PICKING → PACKED → DISPATCHED → DELIVERED` state machine behind operator HTTP endpoints, with optimistic-concurrency (`If-Match`) protection against two operators racing the same fulfillment, and cancels itself in reaction to a cancellation request as long as it's still before dispatch.

Every consumer in every service has a bounded-retry-then-dead-letter path (Spring Kafka's `@RetryableTopic`) and an ADMIN-only, audited endpoint to safely replay a dead-lettered event by id — never an arbitrary client-supplied payload. Order Service also runs a reconciliation job, guarded by a Postgres advisory lock so only one running instance ever acts on a given pass, that finds orders stuck beyond a configurable threshold and either safely retries the last compensation step or escalates to `REQUIRES_REVIEW` with a deduplicated operations incident. Full details, diagrams, and the reasoning behind each decision are in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), [`docs/DOMAIN_MODEL.md`](docs/DOMAIN_MODEL.md), and [`docs/adr/`](docs/adr/).

Order Service's operations projection (`OrderOperationsProjection`/`OrderStageDuration`) is kept in sync inside the same transactions that already write `orders`/`order_status_history` on every lifecycle event, so it's atomic with the facts it's derived from and inherits idempotency from the same Kafka inbox dedup everything else here already relies on — no separate mechanism. It's fully rebuildable (`POST /api/v1/admin/operations-projection/rebuild`, ADMIN-only) from this service's own durable tables rather than by replaying Kafka (whose retention here is finite): every event this service has ever processed already left a durable row before it advanced past it. Low-stock visibility follows the same events-only principle: Inventory Service emits `InventoryLowStock.v1` (edge-triggered on crossing a configured threshold) rather than Order Service ever querying Inventory Service directly. Expensive KPI aggregate reads are cached in Redis (`KpiCache`, TTL-only, the same fail-open-to-PostgreSQL shape as Inventory Service's own availability cache); the backlog, stuck-orders, and work-queue reads stay uncached since operators need them fresh. See [`docs/KPI_DICTIONARY.md`](docs/KPI_DICTIONARY.md) for every KPI's exact formula and [`docs/runbooks/INCIDENT_MANAGEMENT.md`](docs/runbooks/INCIDENT_MANAGEMENT.md) for the incident lifecycle.

## Local development prerequisites

Verified locally:

- Java 21 (JDK) — Maven is bundled via `./mvnw` / `mvnw.cmd`, no separate install needed
- Docker and Docker Compose

```
cp .env.example .env
make infra-up              # PostgreSQL, Kafka, Redis, Keycloak — waits until all are healthy
make run-order              # or run-inventory / run-payment / run-fulfillment, each in its own terminal
make smoke                  # start all four services, exercise JWT auth, then stop them
make smoke-inventory        # create stock, place a real order, observe the InventoryReserved.v1 event
make smoke-payment          # place a normal and a seeded-decline order, observe both payment outcomes
make smoke-fulfillment      # follow an order to a real FulfillmentAssigned.v1 event
make smoke-cancellation     # request cancellation and follow the compensation saga to CANCELLED
make smoke-operations       # exercise the ops API: KPI overview, work queue, backlog, timeline, rebuild
./mvnw -B clean verify      # format check, build, unit + Testcontainers integration tests
```

Each service exposes `GET /actuator/health` (public) and `GET /api/v1/whoami` (requires a bearer token) once running — `order-service` on port 8081, `inventory-service` on 8082, `payment-service` on 8083, `fulfillment-service` on 8084. Full startup order and troubleshooting: [`docs/runbooks/local-infrastructure.md`](docs/runbooks/local-infrastructure.md).

### Operations console

With `make infra-up` and all four services running, start the console:

```
cd apps/ops-console
npm install
npm run dev                 # http://localhost:5173, sign in as operator.demo
scripts/seed-demo-data.sh   # from the repo root — deterministic fictional demo data
npm test                    # Vitest component/unit tests
npm run e2e                 # Playwright, against the real local stack
npm run screenshots         # captures docs/screenshots/*.png from the real stack
```

Requires Node.js LTS. `operator.demo`'s password is `OperatorDemo!123` (as fictional and local-only as every other credential in `infra/keycloak/realm-export.json` — see [`docs/runbooks/local-infrastructure.md`](docs/runbooks/local-infrastructure.md)).

Minimal Dockerfiles exist for all four services (`services/*/Dockerfile`, `make docker-build`), and `infra/compose/docker-compose.yml` runs the platform infrastructure — but the four services themselves aren't part of that Compose stack yet; they run directly on the host against it.

## Roadmap

| Phase | Outcome |
|---|---|
| 0 | Product charter, architecture, agent rules — **complete** |
| 1 | Buildable four-service monorepo — **complete** |
| 2 | Reproducible local infrastructure and migrations — **complete** |
| 3 | Versioned events, outbox/inbox, correlation — **complete** |
| 4 | Secure, idempotent Order Service — **complete** |
| 5 | Race-safe Inventory Service — **complete** |
| 6 | Resilient Payment Service simulator — **complete** |
| 7 | Fulfillment workflow and operator controls — **complete** |
| 8 | Saga compensation and recovery — **complete** |
| 9 | Operations read model and SLA APIs — **complete** |
| 10 | Professional operations console — **complete** |
| 11 | Metrics, traces, alerts, load/failure tests *(planned)* |
| 12 | CI/CD, supply-chain checks, deployment packaging *(planned)* |
| 13 | Documentation, screenshots, demo, resume proof *(planned)* |
| 14 | Claude final adversarial audit *(planned)* |
| Final | Cursor independent wrap-up *(planned)* |

Full detail per phase, including resume signal and acceptance criteria: [`docs/PHASE_STATUS.md`](docs/PHASE_STATUS.md).

## Project documents

- [`docs/PROJECT_CHARTER.md`](docs/PROJECT_CHARTER.md) — business problem, users, success criteria, scope.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — system design and diagrams.
- [`docs/DOMAIN_MODEL.md`](docs/DOMAIN_MODEL.md) — entities, statuses, events, invariants, compensation.
- [`docs/KPI_DICTIONARY.md`](docs/KPI_DICTIONARY.md) — exact formulas for every operations KPI.
- [`docs/adr/`](docs/adr/) — architecture decision records.
- [`docs/runbooks/local-infrastructure.md`](docs/runbooks/local-infrastructure.md) — local infra startup order and troubleshooting.
- [`docs/runbooks/INCIDENT_MANAGEMENT.md`](docs/runbooks/INCIDENT_MANAGEMENT.md) — the operations incident lifecycle.
- [`contracts/events/README.md`](contracts/events/README.md) — the event envelope and per-event JSON Schema contracts, and the versioning rule.
- [`CLAUDE.md`](CLAUDE.md) / [`AGENTS.md`](AGENTS.md) — rules for coding agents working in this repository.

## Engineering conventions

Every coding, editing, refactoring, debugging, testing, migration, scripting, configuration, and code-review task in this repository follows the `plain-readable-code` style — see [`.claude/skills/plain-readable-code/SKILL.md`](.claude/skills/plain-readable-code/SKILL.md) and [`AGENTS.md`](AGENTS.md).
