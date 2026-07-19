# ADR 0001: Four services split by business capability, each owning its own database

## Status

Accepted

## Context

FulfillOps needs to demonstrate real service boundaries, not a single application split into folders. The domain naturally separates into four capabilities with different data, different rates of change, and different failure characteristics: taking orders, tracking stock, authorizing payment, and running warehouse fulfillment.

## Decision

Split the system into four independently deployable services — Order, Inventory, Payment, Fulfillment — plus a frontend ops console. Each service owns exactly one PostgreSQL database and applies its own Flyway migrations. No service is granted network or credential access to another service's database. All cross-service communication happens through a service's public HTTP API or through Kafka events it publishes.

## Consequences

- Services can be deployed, scaled, and reasoned about independently.
- Every cross-service fact must be either requested over HTTP or learned from an event — there is no shortcut through a shared table, which is what makes the outbox/inbox and choreography decisions (ADR 0002, ADR 0003) necessary rather than optional.
- Some duplication of data is expected and accepted (e.g., Order Service keeps a read-only projection of fulfillment status) in exchange for independence.
