# Runbook: Stuck Orders

Covers what to do when `ReconciliationService` (Order Service) has flagged one or more orders as
stuck — no forward progress past `app.reconciliation.stuck-threshold` (demo default `PT30M`), or a
cancellation left unresolved past `app.reconciliation.cancellation-stuck-threshold` (demo default
`PT10M`). See [`INCIDENT_MANAGEMENT.md`](INCIDENT_MANAGEMENT.md) for the incident this always
produces, and `tests/failure-scenarios/stuck-order-reconciliation.sh` for a reversible local demo
of this exact scenario end to end.

## Detection

- **Dashboard**: Grafana → *Order Lifecycle* → "Stuck orders detected / 15m, by stage" and
  "Reconciliation recovery outcomes / 15m".
- **Alert**: `StuckOrderSpike` (more than 3 newly-stuck orders in 15 minutes — a demo threshold)
  and `ReconciliationRunFailing` (the reconciliation job itself errored) in
  `infra/compose/observability/prometheus/rules/fulfillops-alerts.yml`.
- **Metrics**: `reconciliation_stuck_orders_total{stage="..."}`,
  `reconciliation_recovery_outcome_total{outcome="retried"|"escalated"}`, and
  `reconciliation_run_seconds{outcome="completed"|"skipped"|"failed"}`.
- **API**: `GET /api/v1/ops/incidents?kind=COMPENSATION_EXHAUSTED&status=OPEN` or
  `?kind=CANCELLATION_STUCK&status=OPEN`.

## Impact

The customer's order is not progressing (or not finishing its cancellation) even though nothing
about it looks broken from the outside — this is exactly the class of problem an event-driven,
at-least-once system needs a safety net for, since a lost or never-sent event leaves no obvious
error anywhere. One stuck order does not affect any other order; a *spike* of them usually means
one specific downstream service or Kafka topic is the common cause.

## Diagnosis

1. Open the flagged incident(s) (`GET /api/v1/ops/incidents?status=OPEN`) — the `description`
   field already states which stage the order is stuck in, or (for a stuck cancellation) exactly
   which compensations (`inventory release`, `payment refund`, `fulfillment cancellation`) are
   still outstanding.
2. Pull the order's full timeline: `GET /api/v1/ops/orders/{orderId}/timeline` — this merges
   status history and incident history into one chronological view, showing exactly when it last
   moved.
3. Check whether the *expected next event* for that stage ever actually reached Kafka: for an
   order stuck in `INVENTORY_RESERVED` waiting on payment, check whether inventory-service's
   `InventoryReserved.v1` for this order is present on `fulfillops.inventory.events` (via
   `docker exec fulfillops-kafka kafka-console-consumer.sh ...`) and whether payment-service's
   consumer group has actually processed it (no matching entry in payment-service's structured
   logs for that `eventId`/`aggregateId` means it's still waiting, not lost).
4. If it's present and unprocessed: check whether the consuming service was down, is stuck itself
   (e.g. an [outbox backlog](outbox-backlog.md) on its own side), or is failing the same message
   repeatedly (check for a matching row on that service's own [dead-letter topic](dlt-growth.md)).

## Safe action

- **A stuck non-terminal order** (not a cancellation): reconciliation already does the one safe
  thing it can — nothing forward-automated exists here by design, since guessing at "what should
  have happened" is exactly the kind of decision CLAUDE.md reserves for a human. Fix whatever
  actually stalled it (restart the stuck downstream service, replay a dead-lettered event), and
  the order resumes on its own the next time the right event arrives — no manual status edit is
  ever needed or supported.
- **A stuck cancellation**: reconciliation already retried once automatically
  (`republishCancellationRequested`, re-emitting `OrderCancellationRequested.v1` with a fresh
  `causationId`) before escalating to an incident — by the time you're reading this runbook for a
  `CANCELLATION_STUCK`/`COMPENSATION_EXHAUSTED` incident, that automatic retry has already been
  tried. Diagnose why the retry didn't help (same checks as above) before considering anything
  further.
- Never manually update `orders.status` or `order_cancellation` rows directly in the database —
  the whole point of the saga/outbox design is that status only ever changes in response to a
  real event; a manual edit would leave every other service's own view of this order permanently
  inconsistent with Order Service's.

## Validation

Confirm the order's status actually changed (`GET /api/v1/orders/{orderId}`) and that the
matching incident is still open only if the order genuinely hasn't moved — acknowledge/resolve it
once you've confirmed real progress, with a `resolutionNote` describing the root cause (useful for
anyone reading the timeline later).

## Escalation

If `StuckOrderSpike` fires repeatedly across different orders in a short window, look for one
shared cause first (a stopped service, a growing [outbox backlog](outbox-backlog.md), a burst of
[dead-lettered events](dlt-growth.md)) before treating each order as an independent case — fixing
the shared cause usually resolves the whole batch at once.
