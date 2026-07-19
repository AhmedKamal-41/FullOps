# ADR 0004: Design for at-least-once delivery; never claim exactly-once

## Status

Accepted

## Context

Kafka can redeliver a message after a consumer crash, a rebalance, or a retry, even when the consumer already processed it. Some systems attempt to achieve "exactly-once" semantics; in practice this either narrows to a single Kafka-to-Kafka transaction scope or requires assumptions FulfillOps cannot make (no shared database across services, ADR 0001).

## Decision

Assume and design for at-least-once delivery everywhere. No service, document, or test may claim exactly-once processing. Every consumer is required to be idempotent, using the inbox pattern from ADR 0003 plus domain-level uniqueness constraints (e.g., a reservation keyed by `orderId` + `sku`) as a second line of defense.

## Consequences

- Every consumer must be written and tested against redelivery of the same event, not just the happy path.
- Domain constraints (unique keys, conditional updates) double as a safety net if the inbox check is ever bypassed or racy, rather than being the sole line of defense.
- This is honest about a real limitation of the platform rather than a claim that would not survive scrutiny in a technical interview.
