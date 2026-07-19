# Event Contracts

JSON Schema (2020-12) contracts for every event FulfillOps services exchange over Kafka. `EventEnvelope.v1.schema.json` defines the outer envelope; every other `*.v1.schema.json` file extends it with `allOf` + `$ref` and narrows `eventType`, `eventVersion`, `producer`, and `payload`. `Money.v1.schema.json` is a shared value shape referenced by any event that carries a monetary amount.

Every example under `examples/` is validated against its schema by the `contracts` Maven module (`contracts/src/test/java/.../EventSchemaValidationTest.java`) — see that module's own description for what "automated" means here.

## Conventions

- **Money** is `{"currencyCode": "USD", "amount": "19.99"}` — `amount` is always a decimal **string** with exactly 2 fractional digits (matches `java.math.BigDecimal` at scale 2 on the Java side), never a JSON number, so it round-trips exactly with no floating-point drift.
- **Quantities** are JSON integers, never decimals.
- **Timestamps** (`occurredAt`) are UTC `Instant`s, RFC 3339 with a literal `Z` offset — the schema pattern rejects any other offset.
- **IDs** (`eventId`, `correlationId`, `causationId`, `aggregateId`, and any domain ID in a payload) are UUID strings, enforced with an explicit regex `pattern` in addition to `"format": "uuid"` — JSON Schema's `format` keyword is annotation-only by default under the 2020-12 dialect (most validators don't reject malformed values on `format` alone), so the pattern is what actually enforces this.
- **`aggregateId` is always the order ID**, for every event from every service — not each service's own internal identifier (a reservation ID, a payment ID, a fulfillment ID). Those still exist and are carried in `payload` when relevant. Keying every event by the order ID means: all events for one order land on the same Kafka partition (see the root `README`/`ARCHITECTURE.md` for the partitioning convention), or ordering is preserved for the whole saga, and Order Service's operations projection (ADR 0008) can build a per-order view by grouping on one field instead of correlating across four different ID spaces.
- **Reason codes** are a closed `enum` per event, not a free-text field — `reasonDetail` is optional free text for human debugging only, and must never contain PII (no names, emails, addresses, card data). Payloads in general only ever reference people by opaque UUID (`customerId`), never by name or contact detail.
- **No Java serialization, ever.** These schemas are the wire contract; services map to/from plain DTOs, never publish a JPA entity or a `Serializable` Java object.

## Versioning rule

A schema file, once published, is immutable. A backward-compatible change (a new optional field) can be added to the existing `vN` file. Any breaking change — a new required field, a removed field, a changed type or enum, a stricter constraint on existing data — ships as a new `EventName.v(N+1).schema.json` file and a corresponding `eventVersion` bump; the old version keeps validating whatever was already published under it. Nothing in this repository automates enforcing that discipline yet (see `contracts/README.md` for exactly what today's automated tests do and don't cover) — until then, it's enforced by this document and by code review.

## Events in this phase

| Event | Producer | Meaning |
|---|---|---|
| `OrderPlaced.v1` | order-service | A new order was accepted and persisted as `PENDING`. |
| `InventoryReserved.v1` | inventory-service | Stock was reserved for every line item. |
| `InventoryRejected.v1` | inventory-service | At least one line item could not be reserved. No `Reservation` exists to release later. |
| `InventoryReleased.v1` | inventory-service | A prior reservation was released back to available stock. |
| `PaymentAuthorized.v1` | payment-service | The simulated payment was authorized. |
| `PaymentDeclined.v1` | payment-service | The simulated payment was declined — a business rejection, never retried. |
| `PaymentRefunded.v1` | payment-service | A prior authorization was refunded. |
| `FulfillmentAssigned.v1` | fulfillment-service | A fulfillment record was created, status `ASSIGNED`. |
| `FulfillmentStatusChanged.v1` | fulfillment-service | The fulfillment moved to a new status (`PICKING`, `PACKED`, `DISPATCHED`, `DELIVERED`, or `CANCELLED`) — one generic event instead of one type per status; consumers switch on `payload.newStatus`. |
| `OrderCancelled.v1` | order-service | Order Service moved an order to `CANCELLED` after a compensating trigger. |
| `OrderRequiresReview.v1` | order-service | Order Service could not safely auto-resolve an order; an operator must act. |

This list and the per-event descriptions above are kept in sync with `docs/DOMAIN_MODEL.md`'s event catalog and compensation table — that document is the narrative version of the same contract.
