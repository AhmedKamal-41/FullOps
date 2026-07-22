# Event contracts

JSON Schema (2020-12) contracts for every event FulfillOps services exchange over Kafka. These
schemas are the wire contract — services map to and from plain DTOs and never publish a JPA entity or
a serialized Java object. This module holds no production code; it exists to keep the schemas honest.

## Layout

- `events/EventEnvelope.v1.schema.json` — the outer envelope every event shares.
- `events/*.v1.schema.json` — one schema per event, each extending the envelope with `allOf` + `$ref`
  and narrowing `eventType`, `eventVersion`, `producer`, and `payload`.
- `events/Money.v1.schema.json` — a shared value shape referenced by any event carrying an amount.
- `events/examples/` — one example fixture per schema.

## The envelope

Every event is wrapped in `EventEnvelope.v1`, which carries:

`eventId`, `eventType`, `eventVersion`, `occurredAt`, `correlationId`, `causationId`, `aggregateId`,
`producer`, and `payload`.

That is enough to deduplicate (by `eventId`), trace (by `correlationId`/`causationId`), and version
each event type independently.

## Conventions

- **Money** is `{"currencyCode": "USD", "amount": "19.99"}` — `amount` is always a decimal **string**
  with exactly two fractional digits (matching `BigDecimal` at scale 2), never a JSON number, so it
  round-trips exactly with no floating-point drift.
- **Quantities** are JSON integers, never decimals.
- **Timestamps** (`occurredAt`) are UTC `Instant`s, RFC 3339 with a literal `Z` offset — the schema
  pattern rejects any other offset.
- **IDs** (`eventId`, `correlationId`, `causationId`, `aggregateId`, and any domain ID in a payload)
  are UUID strings, enforced with an explicit regex `pattern` in addition to `"format": "uuid"` —
  under the 2020-12 dialect `format` is annotation-only by default, so the pattern is what actually
  rejects a malformed value.
- **`aggregateId` is always the order ID**, for every event from every service — not a reservation,
  payment, or fulfillment ID (those still exist and travel in `payload`). Keying every event by the
  order ID means all events for one order land on the same Kafka partition, so ordering is preserved
  for the whole saga, and Order Service's operations projection can build a per-order view by grouping
  on one field. **One documented exception**: `InventoryLowStock.v1` is SKU-scoped, not order-scoped,
  so there is no order to key by. Its `aggregateId` is a name-based UUID derived from the SKU
  (`UUID.nameUUIDFromBytes`), which still satisfies the envelope's UUID-shaped field and keeps every
  event for one SKU on the same partition; the real `sku` string is in `payload`.
- **Reason codes** are a closed `enum` per event, not free text. `reasonDetail` is optional free text
  for human debugging only and must never contain PII. Payloads reference people only by opaque UUID
  (`customerId`), never by name or contact detail.

## Schema validation

`EventSchemaValidationTest` validates every fixture under `events/examples/` against the schema its
filename names, and confirms every schema has a matching example (`everySchemaFileHasAMatchingExample`,
so a schema can never quietly go undocumented). If a schema and its example drift apart, `./mvnw verify`
fails — not a manual review.

## Versioning

A schema file, once published, is immutable. A backward-compatible change (a new optional field) can
be added to the existing `vN` file. Any breaking change — a new required field, a removed field, a
changed type or enum, a stricter constraint — ships as a new `EventName.v(N+1).schema.json` file and a
corresponding `eventVersion` bump; the old version keeps validating whatever was already published
under it.

Only `v1` schemas exist today, so a real cross-version compatibility check — does a future `v2` still
accept everything `v1` did — is not yet written; there is no second version to check against. When a
second version is introduced, this module should grow a test that loads both and asserts the new one
is a superset of the old one's acceptance. Until then, the versioning rule is enforced by this
document and by code review.

The full event list, producers, and consumers are in
[`../docs/EVENT_CATALOG.md`](../docs/EVENT_CATALOG.md).
