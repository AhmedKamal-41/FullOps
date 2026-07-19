# ADR 0005: Versioned JSON Schema contracts for events, not shared Java classes

## Status

Accepted

## Context

Services need to agree on the shape of the events they exchange. A shared Java module with the event classes is the easiest way to get compile-time safety, but it re-introduces exactly the coupling that ADR 0001 rules out: a change to one service's model becomes a build dependency for every other service, and it becomes tempting to slip a shared JPA entity into the same module.

## Decision

Define every event's envelope and payload as a versioned JSON Schema document in `contracts/events/`. Each service generates or hand-writes its own language-native representation from the schema and validates against it at the service boundary (serialization on publish, deserialization on consume). A breaking payload change ships as a new `eventVersion` (e.g., `OrderPlaced.v2`) rather than mutating `v1` in place.

## Consequences

- Services can evolve their internal models independently; the contract is the only thing that must stay stable.
- Consumers can validate incoming events against the schema and reject or dead-letter malformed ones instead of failing deep inside business logic.
- Requires discipline to keep `contracts/events/` as the source of truth and to version rather than mutate — enforced by contract tests in each service's test suite.
