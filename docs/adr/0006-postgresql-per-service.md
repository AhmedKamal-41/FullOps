# ADR 0006: PostgreSQL, one instance/database per service

## Status

Accepted

## Context

Each service needs a system of record. Options include a single shared PostgreSQL instance with per-service schemas, fully separate PostgreSQL instances, or heterogeneous storage per service (e.g., a document store for one, relational for another).

## Decision

Use PostgreSQL for every service, with each service owning its own database and its own Flyway migration history. Locally, Docker Compose runs one PostgreSQL container per service (or one container with per-service databases, whichever keeps local startup fastest) so that database isolation matches production topology and no service can accidentally query another's tables even in development.

## Consequences

- Strong consistency guarantees (transactions, unique constraints, `SELECT ... FOR UPDATE`) are available within each service for the invariants that matter most — no oversold inventory, no duplicate payment authorization.
- No cross-service joins are possible even by accident, reinforcing the boundary from ADR 0001.
- All four services use the same operational tooling (backup, migration, monitoring approach), which keeps the project's infrastructure surface small and consistent rather than showcasing unnecessary storage diversity.
