# Project Charter

## Business problem

Order fulfillment systems fail in the seams between services: an order gets placed but inventory is never reserved, a payment is charged but fulfillment never starts, or a warehouse update never makes it back to the order the customer is watching. Most portfolio projects skip this entirely and demonstrate only the happy path. FulfillOps exists to build and prove the unhappy path: a small but realistic fulfillment pipeline where failures are expected, detected, and recovered from — with evidence (tests, logs, dashboards) that the recovery actually works, not just a diagram claiming it does.

## Users

- **CUSTOMER** — places an order, tracks its status, and sees an honest reason when something goes wrong (declined payment, out-of-stock item, delayed dispatch).
- **OPERATOR** — works the fulfillment queue day to day: picks, packs, dispatches, and delivers orders, and resolves exceptions that automated compensation could not fully recover (`REQUIRES_REVIEW`).
- **ADMIN** — has cross-service visibility: event streams, dead-letter queues, reconciliation job results, and service health, for diagnosing systemic issues rather than individual orders.

## Success criteria

FulfillOps is successful when, for the current state of the repository:

- The full order lifecycle (placement → reservation → payment → fulfillment → delivery) runs locally via Docker Compose, end to end, with real services and real databases (no mocks standing in for a whole service).
- Common failure modes — inventory shortage, payment decline, message redelivery, consumer crash mid-processing — are handled by documented, tested compensation logic, not left as known gaps.
- Every claim made in documentation (a passing test suite, a load test result, a coverage number) is backed by a command that was actually run in this repository and whose output is captured.
- A reader unfamiliar with the project can go from `docs/ARCHITECTURE.md` to a running system using only the instructions in the README, without asking the author a question.
- The codebase reads as work a competent, careful engineer would ship — not a maximal demonstration of every pattern the author knows.

## In scope

- Four backend domain services (Order, Inventory, Payment, Fulfillment), each with its own PostgreSQL database and Flyway migrations.
- Kafka-based choreography between services using versioned event contracts, transactional outbox on the producer side and idempotent inbox on the consumer side.
- OAuth2/OIDC authentication and role-based authorization (CUSTOMER, OPERATOR, ADMIN) via Keycloak and Spring Security Resource Server.
- Compensation logic for the failure paths described in `docs/DOMAIN_MODEL.md`: inventory release, simulated refund, fulfillment cancellation, and an operator-facing `REQUIRES_REVIEW` state for cases automation cannot resolve.
- A React + TypeScript operations console for order and exception management, backed by an operations read model owned by Order Service.
- Observability: structured logs, Micrometer metrics, OpenTelemetry traces, Prometheus/Grafana dashboards, and basic alerting.
- Automated testing at unit, integration (Testcontainers), and end-to-end (Playwright/REST Assured) levels for every service.
- CI/CD via GitHub Actions, and local-first deployment packaging via Docker Compose (Kubernetes manifests as a later packaging exercise, not a development dependency).
- Documentation sufficient for a stranger to run the system and understand its design: architecture docs, ADRs, runbooks, and a recorded demo.

## Out of scope

- Real payment processing, real payment-card data, or any real customer PII. The payment service is a deterministic simulator only.
- Multi-tenant SaaS concerns (billing, tenant isolation, white-labeling).
- Machine learning, AI-assisted features, or recommendation systems of any kind during the core build (Phases 1–13).
- API gateway products, service mesh, or service discovery infrastructure — services are few enough that direct configuration is clearer than adding these layers.
- GraphQL, gRPC, or any transport beyond REST + Kafka.
- Kubernetes operators, multi-region deployment, or production cloud hosting. Terraform/Kubernetes artifacts, if produced, are packaging exercises evaluated for correctness, not deployed.
- Mobile clients. The operations console is a web application only.
- Horizontal scaling or performance work beyond what is needed to demonstrate correctness under concurrent load in local tests.

## Nonfunctional requirements

- **Correctness under concurrency**: inventory reservation must not oversell under concurrent requests for the same SKU.
- **Delivery semantics**: the system must be correct under Kafka's at-least-once delivery guarantee — no service may assume exactly-once delivery.
- **Idempotency**: every retryable command endpoint must accept an idempotency key, and replays with a different payload under the same key must be rejected as a conflict rather than silently accepted.
- **Data integrity**: monetary values use `BigDecimal`; stock quantities are integers; timestamps are UTC `Instant`; public identifiers are application-generated UUID/ULID values, never raw database sequence IDs.
- **Recoverability**: every failure path must leave the system in a well-defined, recoverable, or explicitly operator-reviewable state — never a silently stuck or duplicated order.
- **Observability**: every service exposes health/readiness endpoints, structured logs, and metrics sufficient to diagnose a stuck order without reading source code.
- **Local reproducibility**: the entire system must start from a clean checkout using Docker Compose and documented commands, without manual, undocumented setup steps.
- **Security baseline**: all inter-service HTTP APIs are authenticated; error responses follow RFC 9457 Problem Details and never leak stack traces, credentials, or internal identifiers beyond what is intended to be public.
- **Truthful reporting**: no performance, coverage, or scale claim is published in documentation until a command run in this repository produced it.
