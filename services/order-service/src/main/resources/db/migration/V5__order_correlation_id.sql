-- The ops console's Order Detail page shows the correlation ID that ties
-- an order's whole saga together. Nothing in this service's schema carried it
-- per-order before now (it only ever existed transiently, per Kafka event, in
-- outbox_event.correlation_id). Nullable, not backfilled: an order placed before
-- this migration genuinely has no recorded correlation ID, and inventing one for it
-- would be exactly the kind of fabricated data this project's engineering rules
-- forbid — the console shows those as "not recorded" rather than a fake value.
ALTER TABLE orders ADD COLUMN correlation_id UUID;
