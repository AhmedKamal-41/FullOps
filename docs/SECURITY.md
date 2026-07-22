# Security model

FulfillOps is a portfolio project with a real security posture for the things that matter in a
fulfillment backend: authentication, authorization, data ownership, and not leaking secrets. It
is explicitly **not** handling real money or real personal data — see [Data protection](#data-protection).

## Authentication

- Every service is a native **Spring Security OAuth2 Resource Server** (not a Keycloak adapter).
  It validates a bearer **JWT** on each request; it never issues tokens.
- Tokens are issued by a local **Keycloak** realm (`fulfillops`) using OIDC. The realm, its
  three fictional demo users, and its clients are in
  [`infra/keycloak/realm-export.json`](../infra/keycloak/realm-export.json).
- Validation checks the **issuer** (`OIDC_ISSUER_URI`) and a custom **audience** requirement:
  the token must carry `fulfillops-api` in its audience, or it is rejected. Keycloak's
  `realm_access.roles` claim is mapped to Spring `ROLE_*` authorities.
- Public endpoints (no token): `GET /actuator/health/**`, `/actuator/info`, and
  `/actuator/prometheus` (a scrape target cannot easily present a bearer token). Everything
  under `/api/**` requires a valid token.

## Authorization

Three roles, enforced both by URL rules in each `SecurityConfig` and by ownership checks in the
service layer:

| Role | Can do |
| --- | --- |
| `CUSTOMER` | Place orders, read/track **their own** orders, request cancellation of their own order. A non-owner gets `404` (not `403`), so they cannot distinguish "not yours" from "doesn't exist". |
| `OPERATOR` | Work the fulfillment queue and the ops exception queue / incident lifecycle; advance fulfillments; cancel before dispatch. Cannot place customer orders. |
| `ADMIN` | Everything an operator can, plus refunds, inventory adjustments, dead-letter list/replay, and operations-projection rebuild. |

Command endpoints that change state require an `Idempotency-Key` (order/refund/adjustment) or
an `If-Match` version (fulfillment) so retries and races cannot double-apply.

## Data protection

- **No real payment or personal data, ever.** No card number, bank detail, or SSN is accepted,
  logged, or stored anywhere — verified by grep across the payment service and the payment event
  schemas. The payment service is a deterministic simulator driven only by the order amount.
- **No secret is logged.** Structured logs carry `correlationId`, `eventId`, `aggregateId`,
  `traceId`/`spanId`, and a safe error classification — never a token or an event payload
  containing customer data.
- **No cross-service data access.** No service reads or writes another service's tables; data
  moves only as events, and payment events carry no card-shaped fields (see
  [`docs/EVENT_CATALOG.md`](EVENT_CATALOG.md)).

## Secrets and configuration

- All credentials in this repo (Keycloak users, database passwords, Redis password, client
  secrets) are **fictional and local-only**, valid only against the local Docker Compose stack.
  None is a real credential.
- Services read database/Kafka/Redis/OIDC settings from **environment variables with no
  defaults** — a missing variable fails startup with a clear error rather than silently using a
  production-looking value. `.env` is git-ignored; only `.env.example` (fictional) is committed.
- In Kubernetes, passwords come from a Secret created out of band; the committed
  `secret.example.yaml` holds only placeholder values and is excluded from the Kustomize build.
  The AWS Terraform uses an RDS-managed master password stored in Secrets Manager.

## Error handling

All HTTP errors use **RFC 9457 Problem Details**. A generic 500 returns a fixed, non-leaking
message; the real exception is logged server-side only. No stack trace or secret is ever
returned to a caller.

## Console (browser)

The ops console signs in with **Authorization Code + PKCE** against Keycloak. Tokens are held
in memory, **never in `localStorage`**, so they are not readable by injected script or left
behind after the tab closes.

## Threat-model summary

A lightweight STRIDE pass over the parts that matter here:

| Threat | Example | Mitigation |
| --- | --- | --- |
| **Spoofing** | Caller forges identity | JWT signature + issuer + `fulfillops-api` audience validation on every request |
| **Tampering** | Client sets its own price/total or customer id | Server computes totals from SKU/quantity/price; request body has no `customerId`/`totalAmount` |
| **Repudiation** | "I didn't make that change" | Append-only status/adjustment history with actor, reason, correlation id, timestamp |
| **Information disclosure** | Card/PII leak, non-owner reads an order | No card/PII fields exist; non-owner reads return `404`; no secrets in logs or Problem Details |
| **Denial of service** | Retry storm, poison message, hot-SKU contention | Bounded retry + DLT per consumer; `SELECT ... FOR UPDATE` serializes hot-SKU contention; idempotency keys |
| **Elevation of privilege** | Customer calls an admin endpoint | Role rules in `SecurityConfig` **and** service-layer ownership checks; verified by authorization tests |

### Out of scope (portfolio boundaries)

Rate limiting, WAF, mTLS between services, secret rotation, and real PII handling are out of
scope — this project does not process real money or real personal data. See
[`docs/KNOWN_LIMITATIONS.md`](KNOWN_LIMITATIONS.md).

## Reporting

See the repository [`SECURITY.md`](../SECURITY.md) for how to report a concern.
