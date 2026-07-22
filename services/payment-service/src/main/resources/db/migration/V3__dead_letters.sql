-- Persisted dead-letter events, so an ADMIN can find one by event id and safely
-- replay the exact bytes that failed — see DeadLetterEventRecorder/DeadLetterReplayService.
-- (event_id, consumer_name) is the primary key because the same event can independently
-- dead-letter for one listener's consumer group without affecting another's.
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
