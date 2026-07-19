# Architecture

> Status: the four backend services are buildable, own their databases, authenticate against a real Keycloak realm, and (as of Phase 3) have working transactional-outbox / idempotent-inbox messaging against real Kafka topics. No business entity, command endpoint, or domain rule is implemented yet — see [`PHASE_STATUS.md`](PHASE_STATUS.md).

## Overview

FulfillOps is four independently deployable domain services plus one frontend, coordinating through Kafka instead of synchronous calls or a shared database:

- **Order Service** — order intake, idempotent order placement, the customer-facing order view, and the operations projection used by the ops console.
- **Inventory Service** — stock levels and concurrency-safe reservation/release.
- **Payment Service** — a deterministic, fictional payment authorization/decline/refund simulator.
- **Fulfillment Service** — the warehouse workflow state machine and operator actions.
- **Ops Console** (`apps/ops-console`) — a React + TypeScript operations UI, talking only to service HTTP APIs (primarily Order Service's operations projection and Fulfillment Service's action endpoints).

Each service owns exactly one PostgreSQL database. No service connects to another service's database, and there is no shared JPA/domain-model module — see [ADR 0001](adr/0001-service-boundaries.md) and [ADR 0002](adr/0002-choreography-not-orchestration.md).

## System context

```mermaid
flowchart TB
    customer([Customer]):::actor
    operator([Operator]):::actor
    admin([Admin]):::actor

    subgraph fulfillops[FulfillOps]
        console[Ops Console<br/>React + TypeScript]
        order[Order Service]
        inventory[Inventory Service]
        payment[Payment Service]
        fulfillment[Fulfillment Service]
        kafka[(Kafka<br/>versioned events)]

        order -->|orderDb| orderDb[(Order DB<br/>PostgreSQL)]
        inventory -->|inventoryDb| inventoryDb[(Inventory DB<br/>PostgreSQL)]
        payment -->|paymentDb| paymentDb[(Payment DB<br/>PostgreSQL)]
        fulfillment -->|fulfillmentDb| fulfillmentDb[(Fulfillment DB<br/>PostgreSQL)]

        order <-->|produce/consume| kafka
        inventory <-->|produce/consume| kafka
        payment <-->|produce/consume| kafka
        fulfillment <-->|produce/consume| kafka
    end

    keycloak[[Keycloak<br/>OIDC identity]]
    redis[(Redis<br/>disposable caches)]

    customer -->|REST, OIDC token| order
    operator -->|REST, OIDC token| console
    admin -->|REST, OIDC token| console
    console -->|REST| order
    console -->|REST| fulfillment

    order -.->|token validation| keycloak
    inventory -.->|token validation| keycloak
    payment -.->|token validation| keycloak
    fulfillment -.->|token validation| keycloak

    order -.->|optional cache| redis
    inventory -.->|optional cache| redis

    classDef actor fill:#eef,stroke:#446,stroke-width:1px;
```

## Service boundaries and data ownership

- Each service owns exactly one PostgreSQL schema/database and applies its own Flyway migrations independently.
- Cross-service reads happen only through a service's public HTTP API or through Kafka events it has chosen to publish — never through direct database access. See [ADR 0001](adr/0001-service-boundaries.md).
- There is no shared JPA entity module. Services that need to agree on shape (e.g., an event payload) share a JSON Schema contract in `contracts/`, not a Java class. See [ADR 0005](adr/0005-json-schema-event-contracts.md).

## Event-driven choreography

Services coordinate through Kafka events rather than a central orchestrator/saga engine. Each service reacts to the events it cares about and emits its own events in response — see [ADR 0002](adr/0002-choreography-not-orchestration.md). Reliable delivery from each service's database transaction to Kafka uses the transactional outbox pattern on the producer side and an idempotent inbox (deduplication by `eventId`) on the consumer side — see [ADR 0003](adr/0003-outbox-inbox.md).

Kafka delivery is at least once, never exactly once. Every consumer must be safe to run twice on the same event — see [ADR 0004](adr/0004-at-least-once-delivery.md). Every event envelope carries `eventId`, `eventType`, `eventVersion`, `occurredAt`, `correlationId`, `causationId`, `aggregateId`, `producer`, and `payload`, which is enough to deduplicate, trace, and version independently per event type. That envelope is a real, JSON-Schema-validated contract — see [`contracts/events/`](../contracts/events/).

### Topics, keys, and retry (implemented in Phase 3)

Each service publishes to exactly one topic, `fulfillops.<service>.events`, keyed by the order ID so every event in one order's saga is ordered on the same partition regardless of which service produced it. `eventId`, `eventType`, `eventVersion`, `correlationId`, and `causationId` ride along as Kafka headers as well as in the JSON body. Consumer retry and dead-lettering use Spring Kafka's own `@RetryableTopic` — transient failures get exponential-backoff retries on auto-created retry topics, and business rejections (a custom `NonRetryableEventProcessingException`) skip straight to the dead-letter topic instead of being retried pointlessly. The full reasoning, including why Resilience4j isn't used, is in [ADR 0009](adr/0009-kafka-topology-and-retry.md).

Each service also runs a scheduled outbox relay (poll due `outbox_event` rows with `FOR UPDATE SKIP LOCKED`, publish, mark sent only after the broker acknowledges) and an inbox check (skip processing if `(event_id, consumer_name)` is already recorded, otherwise process and record in the same transaction) — the mechanism ADR 0003 describes, now real code in every service's `messaging` package. Phase 3 proved the mechanism itself with each service self-consuming its own outbox topic; Phase 5 replaced Inventory Service's scaffold with its first real cross-service listener, `OrderPlacedListener`, consuming `fulfillops.order.events` to reserve stock. Payment and Fulfillment's real cross-service listeners are still later phases.

## Operations projection

Order Service owns a read-optimized operations projection, built by consuming lifecycle events from every other service (inventory, payment, fulfillment). This keeps the ops console's primary data source to one service instead of aggregating live calls to four, at the cost of Order Service needing to consume events it does not otherwise care about for its own domain logic — see [ADR 0008](adr/0008-ops-projection-ownership.md).

## Identity and secrets

Keycloak provides OIDC identity for local development; each backend service is a Spring Security OAuth2 Resource Server validating bearer tokens against it. Three roles are recognized: `CUSTOMER`, `OPERATOR`, `ADMIN` — see [ADR 0007](adr/0007-keycloak-oidc.md). No service stores real payment-card data or real PII; the payment service is a deterministic simulator.

## Redis

Redis is used only for disposable, rebuildable caches (for example, hot-path read caching). No service treats Redis as a system of record — losing the cache must never lose or corrupt data. Inventory Service (Phase 5) is the first concrete example: `GET /api/v1/inventory/{sku}` is a cache-aside read (`InventoryAvailabilityCache`, evicted after every committed reservation/release/adjustment, with a short TTL as a backstop), while PostgreSQL alone — never the cache — decides whether a reservation succeeds. Every Redis call is wrapped so a Redis outage degrades reads straight to PostgreSQL and only shows up as an `inventory.cache.failures` metric, never a failed request.

## Frontend

`apps/ops-console` is a React + TypeScript single-page application for operators and admins. It calls only HTTP APIs (primarily Order Service's operations projection endpoints and Fulfillment Service's operator-action endpoints) and holds no direct database or Kafka access.

## Explicitly excluded

No API gateway, service discovery, GraphQL, Kubernetes operators, or machine learning/AI components are part of this architecture. Kubernetes and Terraform artifacts under `infra/` are later packaging exercises evaluated independently of local development, which runs entirely on Docker Compose.

## Related documents

- [`DOMAIN_MODEL.md`](DOMAIN_MODEL.md) — entities, statuses, events, invariants, and compensation rules, including the order lifecycle and payment-decline compensation diagrams.
- [`adr/`](adr/) — the reasoning behind each boundary and technology decision listed above.
- [`contracts/events/README.md`](../contracts/events/README.md) — the event envelope and per-event JSON Schema contracts referenced above.
