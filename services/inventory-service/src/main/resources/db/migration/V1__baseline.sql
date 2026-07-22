-- Baseline migration: marks database ownership and creates the outbox/inbox
-- tables every service needs for Kafka choreography (see docs/ARCHITECTURE.md). No domain tables yet — those belong
-- to the migration that introduces the owning feature.

COMMENT ON DATABASE inventory_db IS
    'Owned exclusively by inventory-service. No other service may connect to this database. See docs/ARCHITECTURE.md.';

-- Rows this service has produced, written in the same transaction as the domain
-- change they describe, and relayed to Kafka by a separate process. A row moves
-- PENDING -> PUBLISHED once Kafka has confirmed the write, or PENDING -> FAILED
-- (then retried via next_attempt_at) if publishing errors out.
CREATE TABLE outbox_event (
    event_id        UUID PRIMARY KEY,
    event_type      VARCHAR(255) NOT NULL,
    event_version   INTEGER NOT NULL DEFAULT 1 CHECK (event_version > 0),
    aggregate_id    UUID NOT NULL,
    correlation_id  UUID NOT NULL,
    causation_id    UUID,
    producer        VARCHAR(255) NOT NULL,
    payload         JSONB NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL,
    state           VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                        CHECK (state IN ('PENDING', 'PUBLISHED', 'FAILED')),
    attempt_count   INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ,
    last_error      VARCHAR(2000),
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbox_event_aggregate_id ON outbox_event (aggregate_id);
CREATE INDEX idx_outbox_event_state ON outbox_event (state);
CREATE INDEX idx_outbox_event_created_at ON outbox_event (created_at);
CREATE INDEX idx_outbox_event_next_attempt_at ON outbox_event (next_attempt_at);

-- Events this service has consumed from Kafka. The (event_id, consumer_name)
-- primary key is what makes processing idempotent: at-least-once delivery means
-- the same event can arrive twice, and a second arrival for a consumer that
-- already has a PROCESSED row here is a no-op, not a repeat side effect.
CREATE TABLE inbox_event (
    event_id        UUID NOT NULL,
    consumer_name   VARCHAR(255) NOT NULL,
    event_type      VARCHAR(255) NOT NULL,
    aggregate_id    UUID NOT NULL,
    state           VARCHAR(20) NOT NULL DEFAULT 'PROCESSING'
                        CHECK (state IN ('PROCESSING', 'PROCESSED', 'FAILED')),
    attempt_count   INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ,
    last_error      VARCHAR(2000),
    processed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer_name)
);

CREATE INDEX idx_inbox_event_aggregate_id ON inbox_event (aggregate_id);
CREATE INDEX idx_inbox_event_state ON inbox_event (state);
CREATE INDEX idx_inbox_event_created_at ON inbox_event (created_at);
CREATE INDEX idx_inbox_event_next_attempt_at ON inbox_event (next_attempt_at);
