# ADR 0008: Order Service owns the operations projection

## Status

Accepted

## Context

The ops console needs a single, queryable view of every order's current status across inventory, payment, and fulfillment, plus an exception queue for `REQUIRES_REVIEW` cases. Building this by having the console call all four services live on every page load would couple the frontend to every service's availability and API shape, and would make consistent, sortable, filterable views (e.g., "all orders stuck for more than 10 minutes") difficult to implement efficiently.

## Decision

Order Service consumes the lifecycle events from Inventory, Payment, and Fulfillment (in addition to its own) and maintains a read-optimized operations projection table. The ops console's order-management views read from this single projection through Order Service's API rather than aggregating calls to four services.

## Consequences

- The ops console has one dependency for its primary views instead of four, and can filter/sort/paginate server-side.
- Order Service takes on a responsibility slightly broader than "own order data" — it also maintains a read model over facts it does not own the write path for. This is scoped narrowly (read-only projection, never used to make authoritative decisions about inventory or payment state) to avoid becoming a second source of truth.
- The projection can lag behind the owning service by however long event processing takes; this is an accepted, documented eventual-consistency window, not treated as a bug.
