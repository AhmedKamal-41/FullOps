# Runbook: Incident Management

Covers the operations incident lifecycle in Order Service (`operations_incident`,
`incident_action_history`) and the `/api/v1/ops/incidents/**` API. See
[`DOMAIN_MODEL.md`](../DOMAIN_MODEL.md)'s compensation rules table for what actually creates an
incident, and [`KPI_DICTIONARY.md`](../KPI_DICTIONARY.md) for how incidents feed the recovery-
success/manual-touch KPIs.

## What an incident is

An incident is Order Service recording "this order needed — or needs — a human decision, because
it could not safely resolve the situation on its own." It is always tied to one order
(`operations_incident.order_id`) and one of three kinds:

| Kind | Created by | Meaning |
|---|---|---|
| `CANCELLATION_AFTER_DISPATCH` | `OrderCancellationService`, synchronously | Cancellation was requested at or after `DISPATCHED` — goods are physically in transit, so this is never automated. |
| `COMPENSATION_EXHAUSTED` | `ReconciliationService` | An order made no progress (or a cancellation stayed unresolved) past its configured threshold, and either there was nothing left to safely retry, or a retry was already tried and it still didn't work. |
| `CANCELLATION_STUCK` | `ReconciliationService` | A cancellation has been pending past `app.reconciliation.cancellation-stuck-threshold`. Reconciliation's one safe recovery action (re-publishing `OrderCancellationRequested.v1`) fires alongside this — see the KPI dictionary's "recovery success rate" for how the outcome of that retry is measured, after the fact, from the order's own final status rather than from this incident ever being resolved automatically.

At most one **unresolved** incident of a given kind exists per order at once — enforced by a
partial unique index (`V4__operations.sql`), not just application logic, so a concurrent detection
race can never create a duplicate.

## Lifecycle

```
OPEN --acknowledge--> ACKNOWLEDGED --resolve--> RESOLVED
OPEN ------------------------------resolve----> RESOLVED
```

- **OPEN** — the incident exists and nobody has acted on it yet. Written automatically the moment
  `IncidentService` creates the row (an `OPENED` row is written to `incident_action_history` at
  the same time, actor `system`).
- **ACKNOWLEDGED** — an operator has seen it and is on it. Re-acknowledging an already-acknowledged
  incident is allowed (harmless — e.g. a different operator double-checking); only `RESOLVED` is
  terminal.
- **RESOLVED** — an operator has closed it out, with an optional free-text `resolutionNote`. Every
  action (including the initial `OPENED`) leaves one `incident_action_history` row, so the full
  lifecycle — who did what, when — is always reconstructable via
  `GET /api/v1/ops/orders/{orderId}/timeline`, which merges it with the order's own status-change
  history into one chronological view.

Resolving recalculates the affected order's `order_operations_projection.open_incident_count`
(a plain `COUNT`, not an incremented/decremented running tally, so it can never drift out of sync).

## API

All endpoints are OPERATOR/ADMIN only (`SecurityConfig`).

- `GET /api/v1/ops/incidents?status=&kind=&page=&size=` — paginated, optionally filtered.
- `POST /api/v1/ops/incidents/{incidentId}/acknowledge` — no body.
- `POST /api/v1/ops/incidents/{incidentId}/assign` — body `{"assignee": "operator.demo"}`.
- `POST /api/v1/ops/incidents/{incidentId}/resolve` — optional body
  `{"resolutionNote": "..."}`.

Acting on an already-`RESOLVED` incident returns `409 Conflict`
(`IncidentAlreadyResolvedException`). Acting on an unknown incident id returns `404`
(`IncidentNotFoundException`).

## What to actually do for each kind

**`CANCELLATION_AFTER_DISPATCH`**: the order is out for delivery. Once it either arrives or is
confirmed lost/returned, resolve the incident and, if the customer still wants it cancelled, handle
that as a return through Fulfillment Service's normal (non-cancellation) flow — see
`docs/DOMAIN_MODEL.md`'s note on this in the compensation rules table.

**`COMPENSATION_EXHAUSTED`**: something didn't complete after the automated retry gave up. Check
`GET /api/v1/ops/orders/{orderId}/timeline` for the full event history, and each of the other three
services' own `GET /api/v1/admin/dead-letters` for anything that dead-lettered along the way — a
stuck compensation is often a dead-lettered event waiting for `POST
/api/v1/admin/dead-letters/{eventId}/replay` on the right service.

**`CANCELLATION_STUCK`**: reconciliation already retried once automatically. If the order is now
`CANCELLED`, this incident can simply be resolved (the retry worked, even though nothing resolved
it for you — see the lifecycle note above). If it's `REQUIRES_REVIEW`, a matching
`COMPENSATION_EXHAUSTED` incident was also opened — treat that one as primary and resolve both once
the underlying issue is actually fixed.

## Rebuilding the projection doesn't touch incidents

`POST /api/v1/admin/operations-projection/rebuild` recomputes `order_operations_projection`/
`order_stage_duration` only — `operations_incident`/`incident_action_history` are read from, never
written by, a rebuild (see `OperationsProjectionRebuildService`). A rebuild is safe to run at any
point in an incident's lifecycle.
