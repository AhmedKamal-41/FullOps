# FulfillOps

**Event-driven order fulfillment and reliability platform.**

> **Status: Phase 6 complete — Payment Service authorizes, declines, and refunds fictional payments with retry and circuit-breaker resilience.** The four services build, migrate their own databases, enforce JWT authentication against a real Keycloak realm, and publish/consume real Kafka events through a working transactional outbox and idempotent inbox, with JSON-Schema-validated contracts for every event. Order Service places orders idempotently (`POST /api/v1/orders`); Inventory Service consumes `OrderPlaced.v1` and reserves stock with a row-locking concurrency strategy proven safe under real concurrent contention. Payment Service now has real business logic too: it builds a local order-context projection from `OrderPlaced.v1`, authorizes payments deterministically (never randomly) after `InventoryReserved.v1`, separates business declines from transient technical failures with a bounded in-process retry and circuit breaker (Resilience4j's framework-agnostic core libraries, not its Spring Boot starter — see ADR 0010), and exposes an idempotent OPERATOR/ADMIN refund command plus OPERATOR/ADMIN read endpoints for payment status and attempt history. It never accepts, logs, or persists a card number, bank detail, or SSN. Fulfillment Service still does nothing domain-specific yet. Every feature described below that isn't running code is explicitly labeled **(planned)**. See [`docs/PHASE_STATUS.md`](docs/PHASE_STATUS.md) for what is actually done and how it was verified.

## Product pitch

FulfillOps is a portfolio-grade simulation of how a real e-commerce fulfillment backend stays correct under failure. It follows an order from placement through inventory reservation, payment authorization, and warehouse dispatch — as four independently deployable services that coordinate through Kafka events instead of shared databases or synchronous call chains. When a step fails partway through, FulfillOps compensates: it releases reserved stock, refunds the simulated payment, and gives an operator a queue of exceptions to resolve, instead of silently losing or duplicating the order.

The project exists to demonstrate two things concretely, with working code and tests, not just diagrams:

1. **Java backend engineering** — service boundaries, transactional outboxes, idempotent consumers, concurrency-safe inventory updates, and recoverable failure handling.
2. **Operations systems thinking** — an operator console and analytics surface for a team that has to keep a fulfillment pipeline running, not just a set of REST endpoints.

## Target users

- **CUSTOMER** — places orders (`POST /api/v1/orders`, live) and tracks their status *(order-status tracking beyond `PENDING` is planned, pending the later phases that consume inventory/payment/fulfillment lifecycle events)*.
- **OPERATOR** — works the fulfillment queue, moves orders through `PICKING` → `PACKED` → `DISPATCHED` → `DELIVERED`, and resolves exceptions. *(planned)*
- **ADMIN** — has visibility into every service, event stream, and reconciliation job. *(planned)*

## Core workflow

1. A customer submits an order using an idempotency key. **(live)**
2. Order Service validates the request and persists a `PENDING` order and an `OrderPlaced.v1` outbox event in the same transaction. **(live)**
3. Inventory Service reserves stock with a concurrency-safe update and emits `InventoryReserved.v1` or `InventoryRejected.v1`. **(live)**
4. Payment Service authorizes a fictional payment after reservation and emits `PaymentAuthorized.v1` or `PaymentDeclined.v1`. **(live)**
5. Fulfillment Service creates a fulfillment record after authorization and emits `FulfillmentAssigned.v1`. *(planned)*
6. An operator advances the fulfillment through `PICKING`, `PACKED`, `DISPATCHED`, and `DELIVERED`. *(planned)*
7. Order Service consumes lifecycle events and exposes both the customer's order view and an operations projection. *(planned)*
8. Any failure triggers compensation — release inventory, refund the simulated payment, cancel fulfillment where allowed, or mark the order `REQUIRES_REVIEW` for an operator. *(planned)*
9. Retry topics, dead-letter topics, reconciliation jobs, and operator recovery actions keep failures visible instead of silent. *(planned)*

See [`docs/DOMAIN_MODEL.md`](docs/DOMAIN_MODEL.md) for the full state machine and compensation rules.

## Planned architecture

Four independently deployable domain services, each owning its own PostgreSQL database, coordinating through versioned Kafka events with a transactional outbox/inbox pattern:

- **Order Service** — order lifecycle, idempotent order placement, customer-facing order view, operations projection.
- **Inventory Service** — stock levels, concurrency-safe reservation and release.
- **Payment Service** — deterministic fictional payment authorization, decline, and refund simulator.
- **Fulfillment Service** — warehouse workflow state machine and operator actions.
- **Ops Console** — React + TypeScript operations UI for order/exception management. *(planned)*

All four backend services exist today as buildable, independently runnable Spring Boot 4.1.0 / Java 21 applications (`services/`), each with its own PostgreSQL database (migrated by its own Flyway history), native Spring Security OAuth2 Resource Server authentication against a real local Keycloak realm, and a real transactional outbox / idempotent inbox publishing to and consuming from real Kafka topics — with JSON-Schema-validated event contracts in [`contracts/events/`](contracts/events/). Order Service has real business logic: idempotent order placement (`POST /api/v1/orders`), a customer order view (`GET /api/v1/orders`, `GET /api/v1/orders/{orderId}`), and a real `OrderPlaced.v1` event on the wire. Inventory Service consumes that event for real and reserves stock with a `SELECT ... FOR UPDATE` concurrency strategy proven under real concurrent load (`ReservationConcurrencyIT` races 10 orders for 5 units of one SKU and confirms exactly 5 win, never negative stock), plus ADMIN product/adjustment endpoints and a Redis-backed availability cache that degrades to PostgreSQL on outage. Payment Service now has real business logic too: it builds its own local order-context projection from `OrderPlaced.v1` (order id, customer id, currency, amount only — never card data), authorizes payments deterministically after `InventoryReserved.v1` using a documented, seeded, amount-driven simulator rule (the same "magic test amount" convention real card-processor sandboxes use), and wraps the simulated provider call in a bounded retry and circuit breaker built on Resilience4j's framework-agnostic core libraries — not its Spring Boot starter, since no Spring Boot 4.1-verified starter existed on Maven Central when this was written (see [ADR 0010](docs/adr/0010-payment-simulator-resilience.md)). An idempotent OPERATOR/ADMIN refund command (`POST /api/v1/payments/{paymentId}/refunds`) and OPERATOR/ADMIN read endpoints for payment status and attempt history round it out. Fulfillment Service still contains no domain logic or command endpoints *(planned)* — its messaging round-trip is still proven only with a self-test listener, not real cross-service wiring. No service reads or writes another service's tables, and there is no shared JPA/domain-model module. Full details, diagrams, and the reasoning behind each decision are in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and [`docs/adr/`](docs/adr/).

## Local development prerequisites

Verified locally:

- Java 21 (JDK) — Maven is bundled via `./mvnw` / `mvnw.cmd`, no separate install needed
- Docker and Docker Compose

```
cp .env.example .env
make infra-up              # PostgreSQL, Kafka, Redis, Keycloak — waits until all are healthy
make run-order              # or run-inventory / run-payment / run-fulfillment, each in its own terminal
make smoke                  # or: start all four services itself, exercise JWT auth, then stop them
make smoke-inventory        # create stock, place a real order, observe the InventoryReserved.v1 event
./mvnw -B clean verify      # format check, build, unit + Testcontainers integration tests
```

Each service exposes `GET /actuator/health` (public) and `GET /api/v1/whoami` (requires a bearer token) once running — `order-service` on port 8081, `inventory-service` on 8082, `payment-service` on 8083, `fulfillment-service` on 8084. Full startup order and troubleshooting: [`docs/runbooks/local-infrastructure.md`](docs/runbooks/local-infrastructure.md).

Not yet required, but coming in a later phase:

- Node.js LTS (for `apps/ops-console`) *(planned)*

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
| 6 | Resilient Payment Service simulator **(live)** |
| 7 | Fulfillment workflow and operator controls *(planned)* |
| 8 | Saga compensation and recovery *(planned)* |
| 9 | Operations read model and SLA APIs *(planned)* |
| 10 | Professional operations console *(planned)* |
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
- [`docs/adr/`](docs/adr/) — architecture decision records.
- [`docs/runbooks/local-infrastructure.md`](docs/runbooks/local-infrastructure.md) — local infra startup order and troubleshooting.
- [`contracts/events/README.md`](contracts/events/README.md) — the event envelope and per-event JSON Schema contracts, and the versioning rule.
- [`CLAUDE.md`](CLAUDE.md) / [`AGENTS.md`](AGENTS.md) — rules for coding agents working in this repository.

## Engineering conventions

Every coding, editing, refactoring, debugging, testing, migration, scripting, configuration, and code-review task in this repository follows the `plain-readable-code` style — see [`.claude/skills/plain-readable-code/SKILL.md`](.claude/skills/plain-readable-code/SKILL.md) and [`AGENTS.md`](AGENTS.md).
