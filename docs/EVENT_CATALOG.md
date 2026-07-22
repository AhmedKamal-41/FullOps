# Event Catalog

Every cross-service reaction in FulfillOps travels as a versioned Kafka event. There is no
shared database and no orchestrator — each service reacts to the events it cares about and
emits its own. This catalog lists every event, its producer, its consumers, and its schema.

- **Envelope:** every event is wrapped in `EventEnvelope.v1` (`eventId`, `eventType`,
  `eventVersion`, `occurredAt`, `correlationId`, `causationId`, `aggregateId`, `producer`,
  `payload`). Schema: [`contracts/events/EventEnvelope.v1.schema.json`](../contracts/events/EventEnvelope.v1.schema.json).
- **Schemas:** one JSON Schema per event under [`contracts/events/`](../contracts/events/), plus a
  shared `Money.v1`. Every schema has an example fixture that is validated against it by the
  `contracts` module's test (14 tests, run in CI's event-contract job).
- **Delivery:** at least once. Correctness comes from idempotent consumers (an inbox keyed
  by `(event_id, consumer_name)`) and database constraints — **never** from an
  exactly-once claim. See [ADR 0004](adr/0004-at-least-once-delivery.md).
- **Key:** every event is keyed by order id, so all events for one order keep their relative
  order on a partition.

## Topics

One topic per producing service. A service only ever writes to its own topic (via its
transactional outbox); other services consume it.

| Topic | Producer |
| --- | --- |
| `fulfillops.order.events` | Order Service |
| `fulfillops.inventory.events` | Inventory Service |
| `fulfillops.payment.events` | Payment Service |
| `fulfillops.fulfillment.events` | Fulfillment Service |

Each topic also has Spring Kafka `@RetryableTopic` retry topics and a `-dlt` dead-letter
topic (see [ADR 0009](adr/0009-kafka-topology-and-retry.md)).

## Events

### Produced by Order Service — `fulfillops.order.events`

| Event | Meaning | Consumed by | Schema |
| --- | --- | --- | --- |
| `OrderPlaced.v1` | An order was accepted (`PENDING`). | Inventory (trigger reservation), Payment (build order-payment context) | [schema](../contracts/events/OrderPlaced.v1.schema.json) |
| `OrderCancellationRequested.v1` | A cancellation needs compensation from the services that hold state for the order. | Inventory (release), Payment (refund), Fulfillment (cancel) | [schema](../contracts/events/OrderCancellationRequested.v1.schema.json) |
| `OrderCancelled.v1` | The order reached the terminal `CANCELLED` state. | (terminal fact; no automated consumer) | [schema](../contracts/events/OrderCancelled.v1.schema.json) |
| `OrderRequiresReview.v1` | The order could not be auto-resolved and needs an operator. | (terminal fact; surfaced via the ops incident API) | [schema](../contracts/events/OrderRequiresReview.v1.schema.json) |

### Produced by Inventory Service — `fulfillops.inventory.events`

| Event | Meaning | Consumed by | Schema |
| --- | --- | --- | --- |
| `InventoryReserved.v1` | Stock reserved for the order. | Order (advance status), Payment (trigger authorization) | [schema](../contracts/events/InventoryReserved.v1.schema.json) |
| `InventoryRejected.v1` | Not enough stock; nothing reserved. | Order (finalize to `CANCELLED`) | [schema](../contracts/events/InventoryRejected.v1.schema.json) |
| `InventoryReleased.v1` | A prior reservation was released (compensation). | Order (track compensation) | [schema](../contracts/events/InventoryReleased.v1.schema.json) |
| `InventoryLowStock.v1` | A SKU crossed its configured low-stock threshold (edge-triggered). | Order (low-stock visibility in the ops projection) | [schema](../contracts/events/InventoryLowStock.v1.schema.json) |

### Produced by Payment Service — `fulfillops.payment.events`

| Event | Meaning | Consumed by | Schema |
| --- | --- | --- | --- |
| `PaymentAuthorized.v1` | Payment authorized (fictional). | Order (advance status), Fulfillment (trigger assignment) | [schema](../contracts/events/PaymentAuthorized.v1.schema.json) |
| `PaymentDeclined.v1` | Payment declined; a decline reason is carried. | Order (track), Inventory (autonomously release the reservation) | [schema](../contracts/events/PaymentDeclined.v1.schema.json) |
| `PaymentRefunded.v1` | A payment was refunded (compensation). | Order (track compensation) | [schema](../contracts/events/PaymentRefunded.v1.schema.json) |

### Produced by Fulfillment Service — `fulfillops.fulfillment.events`

| Event | Meaning | Consumed by | Schema |
| --- | --- | --- | --- |
| `FulfillmentAssigned.v1` | A fulfillment was created and assigned to a warehouse (`ASSIGNED`). | Order (advance status) | [schema](../contracts/events/FulfillmentAssigned.v1.schema.json) |
| `FulfillmentStatusChanged.v1` | A fulfillment moved to a new status (incl. `CANCELLED`). | Order (advance status), Inventory & Payment (react to a fulfillment cancellation) | [schema](../contracts/events/FulfillmentStatusChanged.v1.schema.json) |

## Data-ownership rule in the payloads

No event payload ever carries a card number, bank detail, or SSN. Payment events carry only
the order id, amount, currency, status, and a decline reason code — nothing card-shaped. See
[`docs/SECURITY.md`](SECURITY.md).

## Versioning

Event names carry a `.v1` suffix and the envelope carries `eventVersion`. A breaking change
means a new `.vN` schema and event type, consumed alongside the old one until producers
migrate — it never mutates an existing schema. A second version does not exist yet, so a real
cross-version compatibility test is not yet written; this is stated plainly in
[`contracts/README.md`](../contracts/README.md).
