-- Inventory Service's own domain tables: fictional products, authoritative stock
-- levels, order reservations and their line items, and an audit trail covering
-- every stock mutation regardless of cause. See docs/DOMAIN_MODEL.md for the
-- StockItem/Reservation model and docs/adr/0006-postgresql-per-service.md for why
-- PostgreSQL (not Redis) is what decides whether a reservation succeeds.

CREATE TABLE product (
    product_id  UUID PRIMARY KEY,
    sku         VARCHAR(64) NOT NULL UNIQUE,
    name        VARCHAR(200) NOT NULL,
    description VARCHAR(1000),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- available_quantity + reserved_quantity is the total stock on hand for the SKU.
-- Both halves are individually non-negative, which is what stops a reservation or
-- a release from ever driving the split below zero. version is the optimistic
-- lock every stock mutation goes through — see ReservationTransaction and
-- StockAdjustmentTransaction for how a concurrent conflict is detected and retried.
CREATE TABLE stock_level (
    stock_level_id     UUID PRIMARY KEY,
    sku                VARCHAR(64) NOT NULL UNIQUE REFERENCES product (sku),
    available_quantity INTEGER NOT NULL DEFAULT 0 CHECK (available_quantity >= 0),
    reserved_quantity  INTEGER NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0),
    version            BIGINT NOT NULL DEFAULT 0,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_stock_level_sku ON stock_level (sku);

-- One row per order that Inventory has reserved stock for. order_id is UNIQUE as a
-- second, database-level line of defense against ever reserving the same order
-- twice, on top of the inbox dedup that OrderPlacedListener already does.
CREATE TABLE inventory_reservation (
    reservation_id UUID PRIMARY KEY,
    order_id       UUID NOT NULL UNIQUE,
    status         VARCHAR(20) NOT NULL CHECK (status IN ('RESERVED', 'RELEASED')),
    version        BIGINT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_inventory_reservation_order_id ON inventory_reservation (order_id);

CREATE TABLE reservation_item (
    reservation_item_id UUID PRIMARY KEY,
    reservation_id       UUID NOT NULL REFERENCES inventory_reservation (reservation_id),
    sku                   VARCHAR(64) NOT NULL,
    quantity              INTEGER NOT NULL CHECK (quantity > 0)
);

CREATE INDEX idx_reservation_item_reservation_id ON reservation_item (reservation_id);

-- Append-only audit trail for every stock mutation, whatever caused it. Written in
-- the same transaction as the stock_level change it describes, so an adjustment,
-- a reservation, and a release are all equally auditable by actor, reason,
-- correlation id, before/after quantity, and timestamp.
CREATE TABLE inventory_adjustment (
    adjustment_id   UUID PRIMARY KEY,
    sku             VARCHAR(64) NOT NULL,
    source          VARCHAR(30) NOT NULL
                        CHECK (source IN ('ADMIN_ADJUSTMENT', 'RESERVATION', 'RELEASE')),
    change_quantity INTEGER NOT NULL,
    quantity_before INTEGER NOT NULL,
    quantity_after  INTEGER NOT NULL,
    reason_code     VARCHAR(50) NOT NULL,
    reason_detail   VARCHAR(500),
    actor           VARCHAR(200) NOT NULL,
    correlation_id  UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_inventory_adjustment_sku ON inventory_adjustment (sku);
CREATE INDEX idx_inventory_adjustment_created_at ON inventory_adjustment (created_at);

-- Same composite-primary-key catch-and-replay shape as order-service's
-- idempotency_requests table, generalized with reference_type since inventory has
-- two idempotent admin commands (product creation, stock adjustment) sharing one
-- ledger, scoped per actor rather than per customer.
CREATE TABLE idempotency_requests (
    actor_id            VARCHAR(200) NOT NULL,
    idempotency_key     VARCHAR(255) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    reference_type      VARCHAR(30) NOT NULL,
    reference_id        UUID NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (actor_id, idempotency_key)
);
