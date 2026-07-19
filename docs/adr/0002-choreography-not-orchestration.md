# ADR 0002: Event choreography instead of a central saga orchestrator

## Status

Accepted

## Context

The order workflow spans four services and must recover from failure at any step. A central saga orchestrator (a service that tells each participant what to do next) is one common answer. An alternative is choreography, where each service reacts to events and emits its own, with no single service directing the others.

## Decision

Use choreography. Each service subscribes to the events it needs and publishes its own events in response. There is no orchestrator service and no central saga state machine owning the whole workflow.

## Consequences

- No single point of control also means no single point of failure for the whole workflow — if one service is down, the others continue processing the events already in flight, and the down service catches up from its Kafka offset when it returns.
- The full workflow is not visible in any one place in code; it has to be understood by reading the event contracts and the state machine documented in `docs/DOMAIN_MODEL.md`. This is an accepted readability cost at four services; it would not scale cleanly to a much larger number of participants, where an orchestrator would likely become the better trade-off.
- Compensation logic lives in the service that can act on it (e.g., Inventory releases its own reservation on `PaymentDeclined.v1`) rather than in a central coordinator.
