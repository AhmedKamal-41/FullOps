# Runbook: Dead-Letter Topic Growth

Covers what to do when events are landing on a service's dead-letter topic (DLT) — the
`dead_letter_event` table any of the four services writes to after a message exhausts its retry
budget (4 attempts, exponential backoff — see each `*Listener`'s `@RetryableTopic`). See
[`INCIDENT_MANAGEMENT.md`](INCIDENT_MANAGEMENT.md) for the separate, order-scoped incident
concept this is not the same thing as — a DLT event is a *messaging* failure, not necessarily an
order-level incident (though it can cause one, later, via reconciliation).

## Detection

- **Dashboard**: Grafana → *Messaging Reliability* → "Dead-lettered events / 5m, by service".
- **Alert**: `DeadLetterGrowth` (`infra/compose/observability/prometheus/rules/fulfillops-alerts.yml`)
  fires the moment `kafka_consumer_dlt_total` increases at all in a 15-minute window — a demo
  default, deliberately sensitive, since even one dead-lettered event in this fictional system is
  worth a human look.
- **Metric**: `kafka_consumer_dlt_total{eventType="..."}` per service, exposed at
  `/actuator/prometheus`.

## Impact

The specific order (or, for `InventoryLowStock`, the specific SKU signal) that message carried
stopped moving forward the moment retries were exhausted — nothing else in the system is affected,
since each consumer's inbox/outbox pattern isolates one message's failure from every other
message on the same topic. Left unaddressed, the affected order will eventually be caught by
[reconciliation](stuck-orders.md) once it's stuck long enough, but that's a slower, indirect
safety net, not a substitute for looking at the actual dead-lettered payload.

## Diagnosis

1. Query the table directly (replace `order_db` with whichever service's database matches the
   dashboard's `service` label):
   ```
   docker exec fulfillops-postgres psql -U "$POSTGRES_SUPERUSER" -d order_db \
     -c "SELECT event_id, consumer_name, event_type, aggregate_id, created_at FROM dead_letter_event WHERE status = 'PENDING_REVIEW' ORDER BY created_at DESC LIMIT 20;"
   ```
2. Or, if that service's DLT replay API is reachable, use
   `GET /api/v1/admin/dead-letters?status=PENDING_REVIEW` (ADMIN only) — see
   `DeadLetterReplayController`.
3. Read the stored `envelope_json` for a specific row to see the exact payload that failed —
   this is the single most useful diagnostic step. Compare it against the event's JSON Schema in
   `contracts/events/` to spot a malformed field, and check the consuming service's own logs
   around that `eventId`/`correlationId` (structured JSON logs — grep by `eventId`) for the actual
   exception that was thrown on every retry attempt.
4. Classify what you find into one of two buckets:
   - **A genuine bug** (e.g. a field the schema requires wasn't populated, a bad enum value) —
     this needs a code fix before replay will ever succeed.
   - **A transient condition that has since cleared** (e.g. a downstream dependency was briefly
     down during a `NonRetryableEventProcessingException`-adjacent edge case) — replay is likely
     to succeed now.

## Safe action

- If the cause was transient and has cleared: replay via
  `POST /api/v1/admin/dead-letters/{eventId}/replay` (ADMIN only) — this re-publishes the exact
  stored envelope back onto its original topic and marks the row `REPLAYED`, never deletes it.
- If the cause is a genuine bug: do **not** replay yet — a replay of a message that will
  deterministically fail again just burns another retry budget and re-lands on the DLT. Fix the
  bug, deploy, then replay.
- Never delete rows from `dead_letter_event` — it is the audit trail of what actually happened
  during the incident (see `DeadLetterReplayService`'s Javadoc for why replay marks a row done
  rather than removing it).

## Validation

After replay, confirm the event was reprocessed successfully: the affected order's status should
advance (`GET /api/v1/orders/{orderId}` or the ops timeline endpoint), and the row's `status`
should now be `REPLAYED` with a `replayed_at` timestamp. If it lands back on the DLT again, the
diagnosis was wrong — go back to step 4 above.

## Escalation

If DLT growth is sustained (the alert keeps re-firing across multiple 15-minute windows) rather
than a single isolated event, treat it as a service-wide issue, not a one-off: check whether a
recent deploy changed an event's shape without a matching schema/version bump, and stop further
replays until the root cause is understood — mass-replaying a systemic problem just relocates the
failure, it doesn't fix it.
