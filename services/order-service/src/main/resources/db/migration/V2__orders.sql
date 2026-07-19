-- Order Service's own domain tables: an order, its line items, its status history,
-- and the idempotency ledger for POST /api/v1/orders. See docs/DOMAIN_MODEL.md for
-- the order status state machine these constraints enforce.

CREATE TABLE orders (
    order_id     UUID PRIMARY KEY,
    customer_id  UUID NOT NULL,
    status       VARCHAR(30) NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN (
                         'PENDING', 'INVENTORY_RESERVED', 'PAYMENT_AUTHORIZED',
                         'FULFILLMENT_ASSIGNED', 'PICKING', 'PACKED', 'DISPATCHED',
                         'DELIVERED', 'CANCELLED', 'REQUIRES_REVIEW'
                     )),
    currency_code   VARCHAR(3) NOT NULL,
    total_amount    NUMERIC(12, 2) NOT NULL CHECK (total_amount >= 0),
    version         INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_orders_customer_id ON orders (customer_id);
CREATE INDEX idx_orders_status ON orders (status);

CREATE TABLE order_items (
    order_item_id UUID PRIMARY KEY,
    order_id      UUID NOT NULL REFERENCES orders (order_id),
    sku           VARCHAR(64) NOT NULL,
    quantity      INTEGER NOT NULL CHECK (quantity > 0),
    unit_price    NUMERIC(12, 2) NOT NULL CHECK (unit_price >= 0),
    line_total    NUMERIC(12, 2) NOT NULL CHECK (line_total >= 0)
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);

-- One row per status the order has ever been in, oldest first. Append-only audit
-- trail — nothing updates or deletes a row here once written.
CREATE TABLE order_status_history (
    order_status_history_id UUID PRIMARY KEY,
    order_id                UUID NOT NULL REFERENCES orders (order_id),
    status                  VARCHAR(30) NOT NULL
                                 CHECK (status IN (
                                     'PENDING', 'INVENTORY_RESERVED', 'PAYMENT_AUTHORIZED',
                                     'FULFILLMENT_ASSIGNED', 'PICKING', 'PACKED', 'DISPATCHED',
                                     'DELIVERED', 'CANCELLED', 'REQUIRES_REVIEW'
                                 )),
    reason_code             VARCHAR(64),
    occurred_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_order_status_history_order_id ON order_status_history (order_id);

-- Ownership of an idempotency key is scoped to the customer who used it (not
-- global) and is immutable once written: the primary key is what makes a second
-- request with the same key from the same customer either a safe replay (if the
-- request_fingerprint matches) or a rejected conflict (if it doesn't) — see
-- OrderService for how the two cases are told apart.
CREATE TABLE idempotency_requests (
    customer_id         UUID NOT NULL,
    idempotency_key     VARCHAR(255) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    order_id            UUID NOT NULL REFERENCES orders (order_id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (customer_id, idempotency_key)
);
