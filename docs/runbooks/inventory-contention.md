# Runbook: Inventory Reservation Contention

Covers a rise in optimistic-lock conflicts while reserving stock (`ReservationService`, retried in
a fresh transaction per attempt — see `ReservationTransaction`'s Javadoc for why) — normal under
concurrent demand for the same popular SKU, a real problem only if it starts exhausting the retry
budget and surfacing as `StockConcurrencyException` (HTTP 409 to the customer).

## Detection

- **Dashboard**: Grafana → *Inventory / Payment* → "Inventory reservation conflicts / min".
- **Metric**: `inventory_reservation_conflict_total` — one increment per optimistic-lock retry,
  not per failed order (an order that eventually succeeds after 3 retries still counts as 3 here).
- A `StockConcurrencyException` surfacing to a customer is the sharper signal: it means the
  configured `app.inventory.reservation.max-attempts` (demo default 20) was fully exhausted for
  that specific reservation attempt.

## Impact

An individual conflict is invisible to the customer — it's absorbed by the automatic retry loop.
Sustained, heavy contention on one SKU slows down every order for that SKU (each conflict costs a
jittered backoff pause — see `RetryBackoff`) and, if the retry budget is ever exhausted, some
customers see an order rejected that a real inventory count would have allowed, purely because of
contention rather than an actual stock shortage.

## Diagnosis

1. Confirm which SKU(s) are contended: reservation conflicts aren't tagged by SKU in the metric
   (to keep cardinality bounded — never label by a per-item identifier that can grow unbounded),
   so cross-reference the timing against `GET /api/v1/inventory/low-stock` or recent order volume
   by SKU to find the likely candidate, or check `stock_level` row's `version` column growth rate
   directly if you have direct DB access.
2. Distinguish two different causes that look identical on this dashboard:
   - **Legitimate demand concentration** — many customers genuinely buying the same popular SKU
     at once. Expected, self-resolving noise.
   - **A stuck or slow transaction holding the row too long** — check for unusually long-running
     queries against `stock_level` (`pg_stat_activity` on the inventory database) rather than many
     short, fast, colliding ones; this is a different problem (a slow query, not contention volume)
     and needs its own investigation, not more retries.
3. If `StockConcurrencyException`s are actually reaching customers, check whether
   `app.inventory.reservation.max-attempts` is simply too low for the traffic level, versus
   something pathological (e.g. a single reservation attempt taking far longer than expected,
   burning through all 20 attempts in an unusually short window).

## Safe action

- For ordinary demand-driven contention: no action needed — the retry loop is the designed
  mitigation, and Compare-and-swap-style optimistic locking (rather than a heavier row lock held
  across a whole HTTP request) is deliberately chosen so throughput degrades gracefully rather than
  serializing all concurrent buyers of the same SKU.
- If the retry budget is being exhausted in practice: raising
  `app.inventory.reservation.max-attempts` is the parameter to reconsider first — it's read at
  startup, so a restart (not a hotfix) is required. Treat this as a deliberate capacity decision,
  not a reflexive knob turn — a much higher value just delays the same problem under heavier load.
- Never bypass the optimistic-lock retry with a direct, unguarded stock update — that's exactly
  the oversell bug this locking design exists to prevent.

## Validation

Confirm the conflict rate returns to its baseline once demand for the contended SKU normalizes,
and — if you changed `max-attempts` — that `StockConcurrencyException`s (HTTP 409 on
`POST /api/v1/orders`) stop reaching customers for that SKU under the same traffic pattern.

## Escalation

If contention is sustained on a SKU that is *not* unusually popular, or reservation attempts are
individually slow rather than merely numerous, escalate as a possible database performance issue
(missing index, lock contention from an unrelated query) rather than ordinary concurrency noise.
