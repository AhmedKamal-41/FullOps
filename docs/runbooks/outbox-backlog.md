# Runbook: Outbox Backlog

Covers a growing backlog of unpublished rows in any service's `outbox_event` table — the
mechanism that guarantees a domain change and the fact that it happened commit together (see
`OutboxEventWriter`) even though the actual Kafka publish happens later, out of band, via
`OutboxRelay`. See `tests/failure-scenarios/kafka-outage-outbox-backlog.sh` for a reversible local
demo of this exact scenario end to end.

## Detection

- **Dashboard**: Grafana → *Messaging Reliability* → "Outbox backlog by service" and "Oldest
  unpublished outbox row age by service".
- **Alert**: `OutboxOldestRowTooOld` (`infra/compose/observability/prometheus/rules/
  fulfillops-alerts.yml`) — an unpublished row older than 5 minutes, a demo threshold.
- **Metrics**: `outbox_backlog_count{service="..."}` and
  `outbox_oldest_unpublished_age_seconds{service="..."}`, refreshed every
  `app.messaging.outbox-metrics-interval-ms` (demo default 15s) by each service's own
  `OutboxBacklogMetrics`.

## Impact

Nothing is lost — every domain write already committed successfully, and the backlog is exactly
the queue of events still waiting to reach Kafka. But every downstream service waiting on one of
these events (an order waiting on `InventoryReserved`, a customer waiting on their order to
progress) is stalled for as long as the row sits unpublished. A backlog that never drains, left
alone, eventually surfaces as [stuck orders](stuck-orders.md) once reconciliation's threshold is
crossed.

## Diagnosis

1. Confirm Kafka itself is actually reachable from the affected service:
   `docker inspect --format='{{.State.Health.Status}}' fulfillops-kafka`, and check that service's
   own logs (structured JSON) for `OutboxRelay` `WARN "failed to publish outbox event"` lines,
   which include the underlying exception message.
2. If Kafka is healthy but the backlog is still growing, check whether this specific service's
   `OutboxRelay` scheduled job is even running — a hung JVM thread or an exception in
   `pollAndPublish()` outside the per-event try/catch would silently stop all further publishing
   from that service. Compare against `app.messaging.outbox-poll-interval-ms` (demo default 500ms)
   — a healthy relay's log should show frequent `"published outbox event type=..."` lines.
3. Check `outbox_event.last_error` for the specific stuck rows directly if you have DB access —
   it's populated on every failed publish attempt (`markFailedAttempt`).
4. Rule out the row simply exhausting its own attempt budget: past
   `app.messaging.outbox-max-attempts` (demo default 5), a row's `state` becomes `FAILED` rather
   than staying `PENDING` — `claimBatch()` only picks up `PENDING` rows, so a `FAILED` row will
   never publish again on its own and needs the explicit safe action below, not just patience.

## Safe action

- **Kafka was down and is back up**: no action needed — `OutboxRelay` retries `PENDING` rows
  automatically on its next poll cycle (with backoff), and the backlog drains on its own. This is
  the whole point of the outbox pattern (see `docs/adr/0004-at-least-once-delivery.md`).
- **Rows reached `FAILED`** (attempt budget exhausted): after confirming the underlying cause is
  actually fixed (Kafka reachable, broker healthy), reset the affected rows back to `PENDING` with
  a fresh `next_attempt_at` so `claimBatch()` picks them up again:
  ```
  docker exec fulfillops-postgres psql -U "$POSTGRES_SUPERUSER" -d <service>_db -c \
    "UPDATE outbox_event SET state = 'PENDING', attempt_count = 0, next_attempt_at = now() WHERE state = 'FAILED';"
  ```
  Do this only after confirming the root cause is resolved — resetting a row whose failure cause
  is still present just burns another attempt budget for nothing.
- Never delete or hand-edit an outbox row's `payload`/`event_id` — replaying the exact original
  event is the entire safety guarantee here; anything else risks publishing a fabricated event
  no domain transaction ever actually committed.

## Validation

Confirm `outbox_backlog_count` for the affected service returns to (or near) zero and
`outbox_oldest_unpublished_age_seconds` resets low. Spot-check a formerly-stuck order actually
advanced (`GET /api/v1/orders/{orderId}`) once its event was published.

## Escalation

If the backlog keeps growing even with Kafka confirmed healthy and the relay confirmed running,
treat it as a possible producer-side problem (serialization failure, a change to `EventEnvelope`
that broke publishing) rather than an infrastructure outage, and stop assuming "it'll drain on its
own" — check `OutboxRelay`'s own exception logs directly rather than just watching the gauge.
