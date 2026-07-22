-- Fulfillment Service's own domain tables. See docs/ARCHITECTURE.md for the
-- fulfillment state machine these constraints enforce.

-- One row per order Fulfillment has taken on. order_id is UNIQUE: PaymentAuthorized.v1
-- for a given order can only ever produce one fulfillment, even if the event is
-- somehow delivered more than once (the inbox table is the first line of defense;
-- this constraint is the database-level backstop, the same role payments.order_id
-- and inventory_reservation.order_id play in the other two services).
CREATE TABLE fulfillments (
    fulfillment_id             UUID PRIMARY KEY,
    order_id                   UUID NOT NULL UNIQUE,
    status                     VARCHAR(20) NOT NULL
                                   CHECK (status IN
                                       ('ASSIGNED', 'PICKING', 'PACKED', 'DISPATCHED', 'DELIVERED', 'CANCELLED')),
    warehouse_id               VARCHAR(64) NOT NULL,
    assignee_id                VARCHAR(255),
    sla_due_at                 TIMESTAMPTZ NOT NULL,
    tracking_reference         VARCHAR(128),
    delivered_at               TIMESTAMPTZ,
    cancellation_reason_code   VARCHAR(64),
    cancellation_reason_detail VARCHAR(500),
    correlation_id             UUID NOT NULL,
    version                    BIGINT NOT NULL DEFAULT 0,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_fulfillments_status ON fulfillments (status);
CREATE INDEX idx_fulfillments_sla_due_at ON fulfillments (sla_due_at);

-- Append-only: one row per status the fulfillment has ever been in, oldest first.
-- Nothing updates or deletes a row here once written, so this is the complete,
-- immutable record of who moved this fulfillment and when.
CREATE TABLE fulfillment_status_history (
    fulfillment_status_history_id UUID PRIMARY KEY,
    fulfillment_id                 UUID NOT NULL REFERENCES fulfillments (fulfillment_id),
    status                         VARCHAR(20) NOT NULL
                                        CHECK (status IN
                                            ('ASSIGNED', 'PICKING', 'PACKED', 'DISPATCHED', 'DELIVERED', 'CANCELLED')),
    actor                           VARCHAR(255) NOT NULL,
    notes                           VARCHAR(500),
    occurred_at                     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_fulfillment_status_history_fulfillment_id
    ON fulfillment_status_history (fulfillment_id);
