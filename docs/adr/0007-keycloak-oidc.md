# ADR 0007: Keycloak for local OIDC identity, Spring Security Resource Server per service

## Status

Accepted

## Context

Customer, operator, and admin roles need real authentication and authorization, not a placeholder header, for the project to demonstrate production-realistic security. Standing up a custom auth server is significant, unnecessary work; an established identity provider is a better use of the project's time.

## Decision

Run Keycloak locally via Docker Compose as the OIDC identity provider, with `CUSTOMER`, `OPERATOR`, and `ADMIN` realm roles. Each backend service is configured as an independent Spring Security OAuth2 Resource Server, validating bearer tokens against Keycloak's issuer directly — no shared authentication service or gateway sits in front of them.

## Consequences

- Token validation and role-based authorization are enforced consistently across services using a well-established library (Spring Security) instead of hand-rolled JWT handling, in line with the project's "don't be cheap about tooling" style rule.
- Each service can be tested for authorization behavior independently (unauthenticated, wrong role, expired token) without standing up the whole system.
- Keycloak is a local-development and demo concern; production identity federation, social login, or multi-tenant realm design are explicitly out of scope (see `PROJECT_CHARTER.md`).
