# Operations runbook

Practical playbooks for the incidents FulfillOps is built to survive. Each follows the same shape:
**symptom → likely cause → diagnose → safe recovery → verify → escalate.** Thresholds and alert
rules named below are demonstration defaults (`infra/compose/observability/prometheus/rules/fulfillops-alerts.yml`),
deliberately sensitive so a single event in this fictional system is worth a look.

Every recovery action here uses the system's real behavior — an audited replay endpoint, a
reconciliation retry, a config change and restart. **Never hand-edit `orders.status`,
`order_cancellation`, an outbox row's payload, or a stock level directly in the database.** The whole
point of the saga and outbox design is that state only changes in response to a real event; a manual
edit leaves every other service's view of that order permanently inconsistent.

## Incident lifecycle

An incident is Order Service recording that an order needed a human decision it could not safely make
on its own. Every incident is tied to one order and one kind:

| Kind | Raised by | Meaning |
|---|---|---|
| `CANCELLATION_AFTER_DISPATCH` | Cancellation service, synchronously | Cancellation requested at or after `DISPATCHED` — goods are in transit, never automated. |
| `COMPENSATION_EXHAUSTED` | Reconciliation | An order made no progress past its threshold and either nothing was left to retry, or a retry already failed. |
| `CANCELLATION_STUCK` | Reconciliation | A cancellation stayed pending past its threshold. Reconciliation's one safe retry (re-publishing `OrderCancellationRequested.v1`) fires alongside it. |

At most one **unresolved** incident of a given kind exists per order — enforced by a partial unique
index, so a detection race can never create a duplicate. The lifecycle is
`OPEN → ACKNOWLEDGED → RESOLVED` (an incident may also be resolved straight from `OPEN`). Every action,
including the initial open, appends a row to the incident action history, so the full "who did what,
when" is reconstructable from `GET /api/v1/ops/orders/{orderId}/timeline`, which merges it with the
order's status history.

**API** (OPERATOR/ADMIN): `GET /api/v1/ops/incidents?status=&kind=&page=&size=`,
`POST .../incidents/{id}/acknowledge`, `.../assign` (`{"assignee": "operator.demo"}`),
`.../resolve` (optional `{"resolutionNote": "..."}`). Acting on an already-resolved incident returns
`409`; an unknown id returns `404`. Rebuilding the operations projection recomputes projection tables
only — it never touches incidents, and is safe to run at any point.

---

## Stuck orders

**Symptom.** The `StuckOrderSpike` alert fires (more than 3 newly-stuck orders in 15 minutes), or an
order sits in a non-terminal stage past `app.reconciliation.stuck-threshold` (demo default 30m), or a
cancellation stays unresolved past `app.reconciliation.cancellation-stuck-threshold` (demo default
10m). Grafana → *Order Lifecycle* → "Stuck orders detected / 15m, by stage".

**Likely cause.** An expected event was never produced or never consumed — a lost publish, a stopped
downstream service, an [outbox backlog](#outbox-backlog) on the producer side, or a
[dead-lettered event](#dead-letter-topic-growth) on the consumer side. This is exactly the class of
problem an at-least-once system needs a safety net for, since a missing event leaves no obvious error.

**Diagnose.**
1. Open the flagged incident (`GET /api/v1/ops/incidents?status=OPEN`) — its `description` states
   which stage the order is stuck in, or which compensations are still outstanding.
2. Pull the order's timeline (`GET /api/v1/ops/orders/{orderId}/timeline`) to see exactly when it
   last moved.
3. Check whether the expected next event actually reached Kafka. For an order stuck in
   `INVENTORY_RESERVED`, confirm `InventoryReserved.v1` is present on `fulfillops.inventory.events`
   and whether Payment Service's consumer processed it (grep its structured logs by
   `eventId`/`aggregateId`).
4. If the event is present but unprocessed, check whether the consumer was down, has its own outbox
   backlog, or is failing the message repeatedly (a matching dead-letter row).

**Safe recovery.**
- A stuck non-terminal order: reconciliation already does the one safe thing it can. Fix whatever
  stalled it (restart the downstream service, replay a dead-lettered event) and the order resumes on
  its own the next time the right event arrives. No manual status edit is needed or supported.
- A stuck cancellation: reconciliation already retried once automatically before escalating. Diagnose
  why the retry didn't help before doing anything further.

**Verify.** The order's status actually advanced (`GET /api/v1/orders/{orderId}`). Acknowledge and
resolve the incident with a `resolutionNote` describing the root cause.

**Escalate.** If `StuckOrderSpike` fires repeatedly across different orders, look for one shared cause
first (a stopped service, a growing outbox backlog, a burst of dead-letters) — fixing it usually
clears the whole batch.

---

## Dead-letter topic growth

**Symptom.** The `DeadLetterGrowth` alert fires, or `kafka_consumer_dlt_total` increases. Grafana →
*Messaging Reliability* → "Dead-lettered events / 5m, by service". A message exhausted its retry
budget (4 attempts, exponential backoff) and its exact bytes were persisted to that service's
`dead_letter_event` table.

**Likely cause.** Either a genuine bug (a field the schema requires wasn't populated, a bad enum
value) that makes the message deterministically fail, or a transient condition that has since cleared.

**Diagnose.**
1. List pending rows (`GET /api/v1/admin/dead-letters?status=PENDING_REVIEW`, ADMIN), or query the
   table directly on the matching service's database.
2. Read the stored envelope to see the exact payload that failed — the single most useful step.
   Compare it against the event's JSON Schema in `contracts/events/`, and check the consuming
   service's logs around that `eventId`/`correlationId` for the actual exception.
3. Classify: genuine bug (needs a code fix before replay can ever succeed) vs. cleared transient
   (replay will likely succeed now).

**Safe recovery.**
- Transient and cleared: replay via `POST /api/v1/admin/dead-letters/{eventId}/replay` (ADMIN). This
  re-publishes the exact stored envelope onto its original topic and marks the row `REPLAYED` — it
  never deletes it and never accepts a client-supplied payload.
- Genuine bug: do **not** replay yet — it just burns another retry budget and re-lands on the DLT.
  Fix, deploy, then replay. Never delete a `dead_letter_event` row; it is the audit trail.

**Verify.** The affected order advances, and the row is now `REPLAYED` with a `replayed_at`
timestamp. If it lands back on the DLT, the diagnosis was wrong — go back to step 3.

**Escalate.** If growth is sustained across multiple windows rather than a one-off, treat it as
service-wide: check whether a recent deploy changed an event's shape without a version bump, and stop
replaying until the root cause is understood — mass-replaying a systemic problem just relocates it.

---

## Outbox backlog

**Symptom.** The `OutboxOldestRowTooOld` alert fires (an unpublished row older than 5 minutes), or
`outbox_backlog_count` climbs. Grafana → *Messaging Reliability* → "Outbox backlog by service".

**Likely cause.** Kafka is unreachable from the service, the relay's scheduled job has stopped, or
rows exhausted their own publish-attempt budget and went to `FAILED`. Nothing is lost — every domain
write already committed; the backlog is the queue of events still waiting to reach Kafka.

**Diagnose.**
1. Confirm Kafka is reachable from the service, and check its logs for relay `WARN "failed to publish
   outbox event"` lines (they include the underlying exception).
2. If Kafka is healthy but the backlog grows, check whether the relay job is actually running — a
   healthy relay logs frequent "published outbox event" lines.
3. Check `outbox_event.last_error` for stuck rows.
4. Rule out attempt-budget exhaustion: past `app.messaging.outbox-max-attempts` (demo default 5) a
   row becomes `FAILED` and is never picked up again on its own.

**Safe recovery.**
- Kafka was down and is back: no action — the relay retries `PENDING` rows on its next cycle and the
  backlog drains on its own. This is the whole point of the outbox pattern.
- Rows reached `FAILED`: after confirming the root cause is fixed, reset them to `PENDING` with a
  fresh `next_attempt_at` so the relay picks them up again:
  ```
  docker exec fulfillops-postgres psql -U "$POSTGRES_SUPERUSER" -d <service>_db -c \
    "UPDATE outbox_event SET state = 'PENDING', attempt_count = 0, next_attempt_at = now() WHERE state = 'FAILED';"
  ```
  Never hand-edit a row's `payload` or `event_id` — replaying the exact original event is the safety
  guarantee.

**Verify.** `outbox_backlog_count` returns to near zero and the oldest-row age resets. Spot-check a
formerly stuck order advanced.

**Escalate.** If the backlog keeps growing with Kafka healthy and the relay running, suspect a
producer-side problem (a serialization failure, a change that broke publishing) and read the relay's
own exception logs rather than waiting for it to drain.

---

## Payment technical failure

**Symptom.** The payment simulator reports `TIMEOUT`/`TEMPORARY_ERROR` (a technical failure, distinct
from a business decline), and the Resilience4j circuit breaker (`payment-provider`) may trip open.
Grafana → *Inventory / Payment* → "Payment attempt outcomes / min" and "circuit breaker state".

**Likely cause.** In this deterministic simulator, technical failures are seeded to specific amounts
(e.g. `9998.00`). A spike concentrated on those amounts is expected demo behavior; a spike spread
across normal amounts would point at a real regression.

**Diagnose.**
1. Read the actual circuit state: `resilience4j_circuitbreaker_state{name="payment-provider"}` — one
   of closed/open/half_open reads `1`.
2. Check whether failures concentrate on seeded amounts (expected) or are broad (a regression in the
   provider adapter or resilience wiring).
3. Inspect `payment_attempt` rows for the affected order — every raw attempt is recorded independently
   of the surrounding transaction's outcome.

**Safe recovery.**
- Circuit closed, failures just retrying: no action — this is the resilience design working.
- Circuit open: do not force retries. After `wait-duration-in-open-state-ms` (demo default 30s),
  Resilience4j probes with trial calls and closes the circuit again if they succeed. Kafka's consumer
  retry means an order rejected while the circuit was open gets a later, less-contended redelivery
  automatically — nothing needs manual replay.

**Verify.** Circuit state back to `closed` on the dashboard, and a normal-priced order reaches
`PAYMENT_AUTHORIZED` again.

**Escalate.** If technical failures happen at amounts with no matching simulator rule, or the circuit
keeps reopening rather than recovering, treat it as a code regression, not transient noise.

---

## Inventory reservation contention

**Symptom.** A rise in optimistic-lock conflicts while reserving stock. Grafana → *Inventory /
Payment* → "Inventory reservation conflicts / min". Normal under concurrent demand for one popular
SKU; a real problem only if it exhausts the retry budget and surfaces `StockConcurrencyException`
(HTTP 409) to a customer.

**Likely cause.** Either legitimate demand concentration on a popular SKU (expected, self-resolving)
or a stuck/slow transaction holding the row too long (a different problem — a slow query, not
contention volume).

**Diagnose.**
1. Identify the contended SKU — conflicts aren't labeled by SKU (bounded cardinality), so
   cross-reference timing against low-stock or recent order volume by SKU.
2. Distinguish demand concentration from a slow transaction: check `pg_stat_activity` on the inventory
   database for unusually long-running queries against `stock_level`.
3. If `StockConcurrencyException`s reach customers, check whether
   `app.inventory.reservation.max-attempts` (demo default 20) is simply too low for the traffic.

**Safe recovery.**
- Ordinary demand-driven contention: no action — the retry loop is the designed mitigation, and
  optimistic locking is chosen so throughput degrades gracefully rather than serializing all buyers.
- Budget genuinely exhausted: raising `max-attempts` is the first parameter to reconsider (read at
  startup, so a restart is required). Treat it as a capacity decision, not a reflexive knob turn.
  Never bypass the retry with a direct unguarded stock update — that is exactly the oversell bug this
  design prevents.

**Verify.** The conflict rate returns to baseline as demand normalizes, and — if you changed
`max-attempts` — 409s stop reaching customers under the same traffic.

**Escalate.** Sustained contention on a SKU that is *not* unusually popular, or individually slow
reservation attempts, points at a database performance issue (a missing index, lock contention from
an unrelated query) rather than ordinary concurrency noise.

---

## Local infrastructure reference

The infrastructure is `infra/compose/docker-compose.yml` (PostgreSQL, Kafka, Redis, Keycloak) and the
four Spring Boot services running against it. Copy `.env.example` to `.env` first — every command
assumes it exists, and all its values are fictional and local-only.

- `make infra-up` — starts Postgres, Kafka, Redis, Keycloak and blocks until all are healthy. The
  first run is slower (Postgres init, one-time Keycloak realm build).
- `make run-order` / `run-inventory` / `run-payment` / `run-fulfillment` — one service per terminal,
  against the infra, sourcing `.env` with `SPRING_PROFILES_ACTIVE=local`.
- `make infra-down` — stops infra, keeping data in named volumes. Add `DOWN_ARGS=-v` to also wipe them.

**Common issues.**
- *"Could not resolve placeholder '...'."* — `.env` is missing a value or was not sourced (use the
  `make run-*` targets, not a bare `./mvnw spring-boot:run`). This fail-fast is intentional.
- *JWT/OAuth2 "issuer" error* — confirm Keycloak is reachable at
  `http://localhost:8080/realms/fulfillops`. The issuer is pinned to `http://localhost:8080` so a
  token's `iss` claim is consistent across network paths.
- *Keycloak health check* — lives on port `9000`, not `8080`.
- *Testcontainers tests fail with a Docker error* — integration tests start their own throwaway
  containers and need a working Docker daemon, independent of `make infra-up`. Confirm `docker ps`
  works.
- *Port already in use (5432/9092/6379/8080)* — stop the conflicting process or change the published
  port in `docker-compose.yml`.

**Fictional credentials.** Every credential in `.env.example`, `docker-compose.yml`, and the Keycloak
realm export is fictional and valid only against this local stack. Demo user passwords:
`admin.demo` / `AdminDemo!123`, `customer.demo` / `CustomerDemo!123`, `operator.demo` /
`OperatorDemo!123`.
