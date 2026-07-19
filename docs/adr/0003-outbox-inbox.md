# ADR 0003: Transactional outbox on publish, idempotent inbox on consume

## Status

Accepted

## Context

A service that writes to its database and then separately publishes a Kafka event has a gap: the database write can succeed while the publish fails (or vice versa), producing a state the rest of the system never learns about, or a duplicate event on retry. Choreography (ADR 0002) depends on events being published reliably and processed safely more than once.

## Decision

Every service that publishes events writes the event to an `outbox` table in the same database transaction as its domain state change, then a separate relay process/thread publishes outbox rows to Kafka and marks them sent. Every service that consumes events records the `eventId` of each event it has successfully processed in an `inbox` table (or equivalent deduplication store) and skips events it has already applied.

## Consequences

- A domain state change and the fact that it happened are never inconsistent — either both are committed or neither is.
- Kafka redelivery (expected under at-least-once delivery, ADR 0004) cannot cause double-reservation, double-charging, or double-fulfillment, because the inbox check makes reprocessing a no-op.
- Adds an outbox relay component and an inbox table to every service — accepted complexity in exchange for correctness that doesn't depend on Kafka's delivery guarantees alone.
