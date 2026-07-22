# Architecture Decision Records

Each ADR captures one significant decision, the alternatives weighed, and why the choice was
made. They are the reasoning behind [`docs/ARCHITECTURE.md`](../ARCHITECTURE.md) and
[`docs/DOMAIN_MODEL.md`](../DOMAIN_MODEL.md).

| # | Decision |
| --- | --- |
| [0001](0001-service-boundaries.md) | Four services split by business capability, each owning its own database |
| [0002](0002-choreography-not-orchestration.md) | Event choreography instead of a central saga orchestrator |
| [0003](0003-outbox-inbox.md) | Transactional outbox on publish, idempotent inbox on consume |
| [0004](0004-at-least-once-delivery.md) | Design for at-least-once delivery; never claim exactly-once |
| [0005](0005-json-schema-event-contracts.md) | Versioned JSON Schema contracts for events, not shared Java classes |
| [0006](0006-postgresql-per-service.md) | PostgreSQL, one instance/database per service |
| [0007](0007-keycloak-oidc.md) | Keycloak for local OIDC identity, Spring Security Resource Server per service |
| [0008](0008-ops-projection-ownership.md) | Order Service owns the operations projection |
| [0009](0009-kafka-topology-and-retry.md) | Kafka topic/key/header conventions, and Spring Kafka's native retry/DLT over Resilience4j |
| [0010](0010-payment-simulator-resilience.md) | Resilience4j's framework-agnostic core libraries, not its Spring Boot starter, for the payment provider call |

New ADRs are added in sequence and never rewritten in place — a superseded decision gets a new
ADR that references the one it replaces.
