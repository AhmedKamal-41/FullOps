# KPI Dictionary

Every number the operations API (`/api/v1/ops/**`, Order Service) returns is defined here exactly —
this document and the code are meant to never drift apart; if they do, this document is wrong, not
the code. All timestamps are UTC `Instant`s throughout; there is no local-timezone handling
anywhere in this API, by design (a client converts to the operator's local zone for display, the
same way every other timestamp in this codebase already works). Every `from`/`to` window is a
half-open-by-convention range applied as `BETWEEN from AND to` in SQL (inclusive of both ends);
`from` must not be after `to` (`InvalidDateRangeException`, HTTP 400) — otherwise the window is
whatever the caller asks for, unbounded in size.

Source: `services/order-service/src/main/java/com/ahmedali/fulfillops/order/service/`
(`KpiOverviewService`, `KpiTimeSeriesService`, `StageDurationKpiService`) and the query methods on
`OrderOperationsProjectionRepository`/`OrderStageDurationRepository`/`OperationsIncidentRepository`.

## Demonstration defaults, not measured targets

SLA thresholds (`app.ops.sla.stage-thresholds` in `application.yml`) and stuck-order age buckets
(`app.ops.sla.age-buckets`) are fictional numbers picked to make the demo data interesting, not
numbers backed by any real measurement or SLA agreement. They are configuration, meant to be
changed per environment. Nothing in this codebase claims a real throughput, latency, or scale
number anywhere. This project publishes measured claims only when a reproducible command in this
repository produces them.

## Orders received / completed / cancelled

**GET `/api/v1/ops/kpis/overview?from=&to=`**

| KPI | Formula | Unit |
|---|---|---|
| Orders received | `COUNT(order_operations_projection WHERE created_at BETWEEN from AND to)` | count |
| Orders completed | `COUNT(... WHERE status = 'DELIVERED' AND current_stage_entered_at BETWEEN from AND to)` | count |
| Orders cancelled | `COUNT(... WHERE status = 'CANCELLED' AND current_stage_entered_at BETWEEN from AND to)` | count |

"Completed"/"cancelled" window on the instant the order *reached* that terminal status
(`current_stage_entered_at`), not on when it was originally placed — an order placed just before
the window and delivered inside it counts as completed in that window, not received.

## End-to-end cycle time and stage duration percentiles

**GET `/api/v1/ops/kpis/stage-durations?from=&to=`**

- **End-to-end cycle time**: order placement to delivery, in seconds —
  `EXTRACT(EPOCH FROM (order_stage_duration.entered_at - orders.created_at))` for every
  `order_stage_duration` row where `stage = 'DELIVERED'` and `entered_at BETWEEN from AND to`
  (DELIVERED is terminal, so that row's own `entered_at` *is* the delivery instant — it never
  closes). p50/p90/p99 via Postgres's own `percentile_cont` (linear interpolation), plus a
  `sampleCount`.
- **Per-stage duration**: for every `OrderStatus` value, p50/p90/p99/`sampleCount` of
  `order_stage_duration.duration_seconds` for rows of that stage with `exited_at BETWEEN from AND
  to` — i.e., stages that *closed* during the window (an order still sitting in a stage doesn't
  contribute a sample until it moves on). A stage with zero closed samples in the window reports
  `sampleCount = 0` and null percentiles, never a fabricated number.

## Open backlog by stage

**GET `/api/v1/ops/backlog`** — a real-time snapshot (uncached), not time-windowed.

`COUNT(order_operations_projection WHERE status = <stage>)` for every stage in
`OrderStatus.OPEN_STAGES` (every non-terminal status except `REQUIRES_REVIEW`, which is surfaced
through the incident queue instead — see "Why REQUIRES_REVIEW is excluded" below).

## SLA breach count/rate by stage

Included in the same `/api/v1/ops/backlog` response. For each open stage with a configured
threshold in `app.ops.sla.stage-thresholds`: `slaBreachedCount` = count of that stage's open orders
where `now - current_stage_entered_at > threshold`; `slaBreachRate` = `slaBreachedCount /
openOrderCount` for that stage (`0.0` if the stage has no open orders). A stage with no configured
threshold always reports `slaBreachedCount = 0` — "no threshold configured" is not the same claim
as "never breaches."

## Stuck orders and age buckets

**GET `/api/v1/ops/kpis/stuck-orders`** — a real-time snapshot.

"Stuck" reuses `app.reconciliation.stuck-threshold` (the same threshold `ReconciliationService`
already uses to decide when to escalate an order) rather than inventing a second, parallel
definition of stuck: an open-stage order is stuck if `now - current_stage_entered_at >
stuck-threshold`. Stuck orders are then grouped into age buckets by `app.ops.sla.age-buckets`
(e.g. `1h-4h`, `4h-24h`, `24h+`) based on that same age.

## Inventory rejection rate and reason distribution

Included in `/api/v1/ops/kpis/overview`.

- **Rate**: `COUNT(projection WHERE created_at BETWEEN from AND to AND
  inventory_rejection_reason_code IS NOT NULL) / ordersReceived` for the same window (`0.0` if
  `ordersReceived = 0`).
- **Distribution**: the same numerator, `GROUP BY inventory_rejection_reason_code` — currently
  always `INSUFFICIENT_STOCK` (the only reason code `InventoryRejected.v1` defines today), but the
  KPI itself is reason-code-driven, not hardcoded to one value, so a future additional reason code
  needs no code change here.

## Payment decline rate vs. technical-failure rate

Included in `/api/v1/ops/kpis/overview`.

Both are scoped to **payment-eligible orders**: orders that reached at least `INVENTORY_RESERVED`
(payment is never attempted before that) — `COUNT(projection WHERE created_at BETWEEN from AND to
AND inventory_rejection_reason_code IS NULL)`. Counting against every order ever placed (including
ones inventory rejected outright, which payment never even sees) would understate both rates.

- **Decline rate**: `COUNT(... AND payment_decline_reason_code IS NOT NULL) / paymentEligibleOrders`.
- **Decline reason distribution**: `GROUP BY payment_decline_reason_code` over the same numerator.
- **Technical-failure rate**: `COUNT(... AND payment_technical_failure_count > 0) /
  paymentEligibleOrders`. This is a **proxy**, documented honestly as one: Order Service only ever
  learns *how many* `TIMEOUT`/`TEMPORARY_ERROR`/`CIRCUIT_OPEN` attempts preceded an order's final
  authorize/decline outcome — a count Payment Service computes from its own `payment_attempts`
  table and includes on `PaymentAuthorized.v1`/`PaymentDeclined.v1` (see
  `contracts/events/PaymentAuthorized.v1.schema.json`'s `precedingTechnicalFailureCount`). It does
  not see individual attempts, nor a technical failure that Payment Service's own retry recovered
  from *without* ultimately producing a terminal outcome tied to a specific order in this window.
  For attempt-level detail, see Payment Service's own Resilience4j/Actuator metrics —
  intentionally not duplicated into this projection.

## Fulfillment throughput

Included in `/api/v1/ops/kpis/overview`.

`COUNT(order_stage_duration WHERE stage = 'DISPATCHED' AND entered_at BETWEEN from AND to)` —
orders that were dispatched in the window, regardless of whether they've reached `DELIVERED` yet.

## DLT / outbox backlog and oldest-message age

Included in `/api/v1/ops/kpis/overview`. **Scoped to Order Service's own tables only** — a
deliberate, documented limitation, not an oversight. A true fleet-wide view would require each of
the other three services to publish its own backlog as a signal, which nothing in this codebase
does yet; each service's own dead-letter admin endpoint (`GET
/api/v1/admin/dead-letters`) is today a separate, per-service surface, not aggregated here.

- **DLT backlog**: `COUNT(dead_letter_event WHERE status = 'PENDING_REVIEW')`; oldest age from the
  oldest such row's `created_at`.
- **Outbox backlog**: `COUNT(outbox_event WHERE state <> 'PUBLISHED')` (still `PENDING` or already
  `FAILED`); oldest age from the oldest such row's `occurred_at`.

## Recovery success and manual-touch rate

Included in `/api/v1/ops/kpis/overview`.

- **Recovery success rate**: of every `CANCELLATION_STUCK` incident opened in the window
  (`operations_incident.created_at BETWEEN from AND to`), what fraction belong to an order whose
  projection status is now `CANCELLED` (meaning `ReconciliationService`'s one automatic retry
  worked) rather than `REQUIRES_REVIEW` (meaning it didn't and an operator had to step in).
  `ReconciliationService` never resolves a `CANCELLATION_STUCK` incident itself either way — the
  order's own final status is the only reliable signal of which outcome happened.
- **Manual-touch rate**: of every order placed in the window, what fraction ever had *any*
  incident opened for it (`operations_incident` joined to `orders` on `created_at BETWEEN from AND
  to`) — a proxy for "needed operator attention at some point," not scoped to any one incident
  kind.

## Time series

**GET `/api/v1/ops/kpis/timeseries?from=&to=&interval=DAY|HOUR`**

Orders received/completed/cancelled, bucketed by `date_trunc('day' | 'hour', <timestamp column>)`
— received buckets by `created_at`, completed/cancelled bucket by `current_stage_entered_at`, each
computed independently and merged by bucket start so a bucket with zero of one kind still gets a
point (never a missing gap). `interval` is a closed two-value enum
(`com.ahmedali.fulfillops.order.domain.TimeSeriesInterval`), never a raw client string passed to
`date_trunc` — the only two Postgres field names that can ever reach that query.

## Low-stock signals

**GET `/api/v1/ops/low-stock`** — every SKU currently below its configured threshold
(`low_stock_signal.below_threshold = true`), the latest state from the most recent
`InventoryLowStock.v1` event for that SKU (see `contracts/events/InventoryLowStock.v1.schema.json`
and its "aggregateId exception" note in `contracts/events/README.md`). Not time-windowed — always
the current state.

## Why `REQUIRES_REVIEW` is excluded from backlog/SLA/stuck-order KPIs

An order in `REQUIRES_REVIEW` didn't get there through normal-flow progress — it got there because
something needed a human decision, which has no meaningful "SLA duration" the way waiting in
`PICKING` does. It's already fully visible through the incident queue
(`GET /api/v1/ops/incidents`), which is the correct exception surface for it, so counting it again
in the backlog/SLA/stuck-orders KPIs would double-count the same exception under two different
labels.

## Caching

Overview, time-series, and stage-duration-percentile reads are cached in Redis
(`app.ops.cache.ttl-seconds`, default 30s) — see `KpiCache`, which mirrors
`InventoryAvailabilityCache`'s shape: every cache key includes the full filter set
(window, interval), and any Redis error falls back to computing directly from PostgreSQL, logged
and counted (`ops.kpi.cache.failures`), never surfaced as an error to the caller. Backlog,
stuck-orders, and the work queue are **not** cached — they're single indexed queries an operator
needs fresh, not worth trading correctness-adjacent staleness for.
