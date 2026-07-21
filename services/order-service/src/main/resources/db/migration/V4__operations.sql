-- Phase 9: the operations read model — a denormalized, rebuildable projection built
-- from the same lifecycle events OrderLifecycleTransaction/OrderCancellationTransaction/
-- OrderRequiresReviewTransaction already apply to orders/order_status_history, plus the
-- incident acknowledge/assign/resolve lifecycle and low-stock visibility. See
-- docs/KPI_DICTIONARY.md for the KPI formulas these tables back.

-- These three facts only ever arrive in a Kafka event payload (InventoryRejected.reasonCode,
-- PaymentDeclined/PaymentAuthorized.reasonCode/precedingTechnicalFailureCount) — nothing else in
-- this service's durable tables records them. Storing them on orders itself (not just the
-- projection) is what makes OperationsProjectionRebuildService's rebuild actually complete:
-- without this, a rebuilt projection would come back with these three columns null for any order
-- that ever had an inventory rejection or payment decline, even though the live-built projection
-- had them set.
ALTER TABLE orders ADD COLUMN inventory_rejection_reason_code VARCHAR(30);
ALTER TABLE orders ADD COLUMN payment_decline_reason_code VARCHAR(30);
ALTER TABLE orders ADD COLUMN payment_technical_failure_count INTEGER NOT NULL DEFAULT 0;

-- One row per order, kept in sync in the same transaction as the order_status_history
-- write that already happens on every lifecycle event — see OperationsProjectionUpdater.
-- Denormalized on purpose: the ops work queue/KPI reads need to filter and sort across
-- many dimensions cheaply, without joining orders/order_status_history/order_cancellation/
-- operations_incident on every request. version exists because three independent Kafka
-- listener threads (inventory/payment/fulfillment topics) can race to update the same
-- order's projection row during cancellation — the same lost-update risk Phase 8 already
-- hit once for order_cancellation (see PHASE_STATUS.md's Phase 8 section) — applied here
-- proactively instead of waiting to rediscover it.
CREATE TABLE order_operations_projection (
    order_id                        UUID PRIMARY KEY REFERENCES orders (order_id),
    customer_id                     UUID NOT NULL,
    status                          VARCHAR(30) NOT NULL,
    currency_code                   VARCHAR(3) NOT NULL,
    total_amount                    NUMERIC(12, 2) NOT NULL,
    created_at                      TIMESTAMPTZ NOT NULL,
    current_stage_entered_at        TIMESTAMPTZ NOT NULL,
    updated_at                      TIMESTAMPTZ NOT NULL,
    inventory_rejection_reason_code VARCHAR(30),
    payment_decline_reason_code     VARCHAR(30),
    payment_technical_failure_count INTEGER NOT NULL DEFAULT 0,
    cancellation_reason_code        VARCHAR(30),
    requires_review_reason_code     VARCHAR(30),
    open_incident_count             INTEGER NOT NULL DEFAULT 0,
    version                         INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_ops_projection_status ON order_operations_projection (status);
CREATE INDEX idx_ops_projection_customer_id ON order_operations_projection (customer_id);
CREATE INDEX idx_ops_projection_created_at ON order_operations_projection (created_at);
-- Backs "orders in stage X that entered before cutoff" — backlog, SLA breach, and
-- stuck-order queries all filter this way.
CREATE INDEX idx_ops_projection_status_stage_entered
    ON order_operations_projection (status, current_stage_entered_at);

-- One row per (order, stage) the order has ever entered — this state machine never
-- revisits a stage, so (order_id, stage) is unique. duration_seconds is computed and
-- stored once, on exit, so percentile queries (Postgres's built-in percentile_cont)
-- never have to recompute it from timestamps at read time.
CREATE TABLE order_stage_duration (
    id               UUID PRIMARY KEY,
    order_id         UUID NOT NULL REFERENCES orders (order_id),
    stage            VARCHAR(30) NOT NULL,
    entered_at       TIMESTAMPTZ NOT NULL,
    exited_at        TIMESTAMPTZ,
    duration_seconds BIGINT,
    UNIQUE (order_id, stage)
);

CREATE INDEX idx_stage_duration_order_id ON order_stage_duration (order_id);
CREATE INDEX idx_stage_duration_stage_duration
    ON order_stage_duration (stage, duration_seconds)
    WHERE duration_seconds IS NOT NULL;
CREATE INDEX idx_stage_duration_stage_exited_at
    ON order_stage_duration (stage, exited_at)
    WHERE exited_at IS NOT NULL;

-- One row per SKU, upserted on every InventoryLowStock.v1 — the latest known low-stock
-- state for that SKU, not an order-scoped history. Deliberately excluded from projection
-- rebuild (see OperationsProjectionRebuildService): rebuild replays orders' own durable
-- history, and this table isn't order-scoped history — it naturally catches up as new
-- InventoryLowStock.v1 events arrive.
CREATE TABLE low_stock_signal (
    sku                VARCHAR(64) PRIMARY KEY,
    available_quantity INTEGER NOT NULL,
    threshold          INTEGER NOT NULL,
    below_threshold    BOOLEAN NOT NULL,
    occurred_at        TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_low_stock_signal_below_threshold ON low_stock_signal (below_threshold);

-- Incident acknowledge/assign/resolve lifecycle. status grows a third state
-- (ACKNOWLEDGED) alongside the existing OPEN/RESOLVED from V3__saga.sql.
ALTER TABLE operations_incident ADD COLUMN acknowledged_at TIMESTAMPTZ;
ALTER TABLE operations_incident ADD COLUMN acknowledged_by VARCHAR(255);
ALTER TABLE operations_incident ADD COLUMN assigned_to VARCHAR(255);
ALTER TABLE operations_incident ADD COLUMN assigned_at TIMESTAMPTZ;
ALTER TABLE operations_incident ADD COLUMN resolved_by VARCHAR(255);
ALTER TABLE operations_incident ADD COLUMN resolution_note VARCHAR(500);

ALTER TABLE operations_incident DROP CONSTRAINT operations_incident_status_check;
ALTER TABLE operations_incident ADD CONSTRAINT operations_incident_status_check
    CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED'));

-- Correctness fix while extending this: under the old OPEN/RESOLVED model, "at most one
-- OPEN incident of a kind per order" was enough to prevent duplicates. Under the new
-- three-state model, an ACKNOWLEDGED-but-unresolved incident must still block a
-- duplicate — an operator who has acknowledged a problem hasn't made it stop being open.
-- So the dedup index (and IncidentService.openOrDeduplicate's matching query) move from
-- status = 'OPEN' to status <> 'RESOLVED'.
DROP INDEX idx_operations_incident_open_kind_per_order;
CREATE UNIQUE INDEX idx_operations_incident_unresolved_kind_per_order
    ON operations_incident (order_id, kind)
    WHERE status <> 'RESOLVED';

-- Append-only: every acknowledge/assign/resolve action taken against an incident, plus
-- an OPENED row written automatically when IncidentService creates one — so the full
-- lifecycle is visible from creation, not just from the first operator action.
CREATE TABLE incident_action_history (
    id          UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES operations_incident (incident_id),
    action      VARCHAR(20) NOT NULL
                    CHECK (action IN ('OPENED', 'ACKNOWLEDGED', 'ASSIGNED', 'RESOLVED')),
    actor       VARCHAR(255) NOT NULL,
    detail      VARCHAR(500),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_incident_action_history_incident_id ON incident_action_history (incident_id);

-- Rebuild run metadata. Named for what it actually tracks: rebuild recomputes
-- order_operations_projection/order_stage_duration from this service's own durable
-- tables (orders, order_status_history, order_cancellation, operations_incident), not
-- from a Kafka consumer offset — there's no "checkpoint" to resume from, only a
-- transactional truncate-and-repopulate. See OperationsProjectionRebuildService.
CREATE TABLE projection_rebuild_run (
    rebuild_id       UUID PRIMARY KEY,
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,
    status           VARCHAR(20) NOT NULL DEFAULT 'RUNNING'
                          CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    triggered_by     VARCHAR(255) NOT NULL,
    orders_processed INTEGER,
    failure_detail   VARCHAR(2000)
);

CREATE INDEX idx_projection_rebuild_run_started_at ON projection_rebuild_run (started_at);
