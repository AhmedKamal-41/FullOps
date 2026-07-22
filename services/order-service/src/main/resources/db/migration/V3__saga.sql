-- The cancellation saga, operations incidents, and dead-letter recovery.
-- See docs/DOMAIN_MODEL.md for the state machine and compensation rules these
-- tables enforce.

-- CANCELLATION_PENDING is a new intermediate status: an order sits here while
-- Order Service waits for whichever of {inventory release, payment refund,
-- fulfillment cancellation} it actually needs to be confirmed by the owning
-- service, before finalizing to CANCELLED. Postgres has no ALTER-CHECK-to-add-
-- one-value shortcut, so the constraint is dropped and recreated with the new
-- value included — the names below are Postgres's own default naming for an
-- unnamed inline column CHECK (<table>_<column>_check), unchanged since
-- V2__orders.sql defined them.
ALTER TABLE orders DROP CONSTRAINT orders_status_check;
ALTER TABLE orders ADD CONSTRAINT orders_status_check
    CHECK (status IN (
        'PENDING', 'INVENTORY_RESERVED', 'PAYMENT_AUTHORIZED',
        'FULFILLMENT_ASSIGNED', 'PICKING', 'PACKED', 'DISPATCHED',
        'DELIVERED', 'CANCELLATION_PENDING', 'CANCELLED', 'REQUIRES_REVIEW'
    ));

ALTER TABLE order_status_history DROP CONSTRAINT order_status_history_status_check;
ALTER TABLE order_status_history ADD CONSTRAINT order_status_history_status_check
    CHECK (status IN (
        'PENDING', 'INVENTORY_RESERVED', 'PAYMENT_AUTHORIZED',
        'FULFILLMENT_ASSIGNED', 'PICKING', 'PACKED', 'DISPATCHED',
        'DELIVERED', 'CANCELLATION_PENDING', 'CANCELLED', 'REQUIRES_REVIEW'
    ));

-- One row per order that has ever entered CANCELLATION_PENDING: which
-- compensations it actually needs (computed once, from what had already
-- happened for that order at the moment cancellation started) and which of
-- those have been confirmed so far. OrderCancellationFinalizer finalizes the
-- order to CANCELLED once every required flag is confirmed. A required flag
-- can later flip from false to true (never the reverse) if a milestone event
-- for this order arrives after cancellation already started — see
-- OrderLifecycleTransaction's handling of that race. version backs optimistic
-- locking: inventory release, payment refund, and fulfillment cancellation
-- confirmations each arrive on a different Kafka topic, consumed on a
-- different listener thread, so two of them can genuinely race to update this
-- same row — without a version check that's a silent lost update (one
-- confirmation's write quietly overwritten by another's stale read), not a
-- visible error, and the order is then stuck in CANCELLATION_PENDING forever.
CREATE TABLE order_cancellation (
    order_id                     UUID PRIMARY KEY REFERENCES orders (order_id),
    requested_by                 VARCHAR(255) NOT NULL,
    reason_detail                VARCHAR(500),
    cancellation_reason_code     VARCHAR(30) NOT NULL
                                     CHECK (cancellation_reason_code IN (
                                         'PAYMENT_DECLINED', 'FULFILLMENT_CANCELLED',
                                         'CUSTOMER_REQUESTED', 'OPERATOR_REQUESTED'
                                     )),
    inventory_release_required  BOOLEAN NOT NULL DEFAULT FALSE,
    inventory_release_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    payment_refund_required     BOOLEAN NOT NULL DEFAULT FALSE,
    payment_refund_confirmed    BOOLEAN NOT NULL DEFAULT FALSE,
    fulfillment_cancel_required  BOOLEAN NOT NULL DEFAULT FALSE,
    fulfillment_cancel_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    requested_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at                  TIMESTAMPTZ,
    version                      INTEGER NOT NULL DEFAULT 0
);

-- An operator-facing exception: something Order Service could not safely
-- auto-resolve. kind is deliberately the same closed set as
-- OrderRequiresReview.v1's reasonCode plus CANCELLATION_STUCK (a reconciliation-
-- only finding, never itself emitted as OrderRequiresReview.v1 until escalated).
-- The partial unique index is what makes incident creation idempotent: at most
-- one OPEN incident of a given kind can ever exist for an order at once.
CREATE TABLE operations_incident (
    incident_id  UUID PRIMARY KEY,
    order_id     UUID NOT NULL REFERENCES orders (order_id),
    kind         VARCHAR(30) NOT NULL
                     CHECK (kind IN (
                         'COMPENSATION_EXHAUSTED', 'CANCELLATION_AFTER_DISPATCH',
                         'CANCELLATION_STUCK'
                     )),
    detail       VARCHAR(500),
    status       VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at  TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_operations_incident_open_kind_per_order
    ON operations_incident (order_id, kind)
    WHERE status = 'OPEN';

-- Every event that reached this service's dead-letter topic, persisted so an
-- ADMIN can look one up by event id and safely replay the exact bytes that
-- failed — never a client-supplied payload. (event_id, consumer_name) is the
-- primary key because the same event can independently dead-letter for one
-- listener's consumer group without affecting another's.
CREATE TABLE dead_letter_event (
    event_id        UUID NOT NULL,
    consumer_name   VARCHAR(255) NOT NULL,
    original_topic  VARCHAR(255) NOT NULL,
    event_type      VARCHAR(255) NOT NULL,
    aggregate_id    UUID NOT NULL,
    envelope_json   TEXT NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING_REVIEW'
                        CHECK (status IN ('PENDING_REVIEW', 'REPLAYED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    replayed_at     TIMESTAMPTZ,
    replayed_by     VARCHAR(255),
    PRIMARY KEY (event_id, consumer_name)
);

CREATE INDEX idx_dead_letter_event_status ON dead_letter_event (status);

-- Idempotency ledger for POST /api/v1/orders/{orderId}/cancellation-requests — a separate table
-- from idempotency_requests (the order-creation ledger) because the two operations must
-- never collide on the same (actor, key) pair: a customer could plausibly reuse a string they
-- already used to place the order when they later cancel it. actor_id is the acting principal's
-- subject (a customer cancelling their own order, or an operator/admin cancelling any order), not
-- necessarily the order's own customer_id.
CREATE TABLE cancellation_idempotency_requests (
    actor_id             VARCHAR(255) NOT NULL,
    idempotency_key      VARCHAR(255) NOT NULL,
    request_fingerprint  VARCHAR(64) NOT NULL,
    order_id             UUID NOT NULL REFERENCES orders (order_id),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (actor_id, idempotency_key)
);
