-- Carries the W3C trace context (traceparent/tracestate) that was active when this outbox row
-- was written, so OutboxRelay can resume it at publish time instead of always starting a fresh
-- trace on its own scheduler thread — this is what lets one order's trace cross the async
-- outbox/Kafka boundary into the next service, not just the synchronous HTTP request that wrote
-- the row. Nullable: absent whenever nothing was actively being traced when the row was written
-- (for example, tracing disabled in tests).
ALTER TABLE outbox_event ADD COLUMN trace_context TEXT;
