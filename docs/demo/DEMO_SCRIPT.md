# FulfillOps — 3–5 minute demo

A deterministic walkthrough for a reviewer. Every step runs through the real HTTP APIs and the
real event choreography — **nothing is written directly to a database**. Payment outcomes are
deterministic because the simulator is driven by seeded, documented amounts (see
[`docs/DOMAIN_MODEL.md`](../DOMAIN_MODEL.md) and `V2__payments.sql`).

Total hands-on time is a few minutes; the seed script does the heavy lifting.

## 0. Prerequisites (once)

```
cp .env.example .env
make infra-up          # PostgreSQL, Kafka, Redis, Keycloak — waits until healthy
```

## 1. Seed a full, deterministic picture (~1 min)

```
scripts/seed-demo-data.sh
```

This starts the four services against the running infra and, through the real APIs, produces
one order of **every shape the console needs to show**: a happy/delivered order, an
inventory-rejected order, a payment-declined order, a low-stock signal, a plain in-flight order,
a `CANCELLATION_AFTER_DISPATCH` incident, and a `CANCELLATION_STUCK` incident (the last uses the
real reconciliation job with shortened demo thresholds, not a manual edit). It is repeatable and
self-contained.

## 2. Open the operations console (~2 min)

```
cd apps/ops-console && npm install && npm run dev     # http://localhost:5173
```

Sign in as **`operator.demo`** (password `OperatorDemo!123`, fictional/local-only). Walk the six
routes — each is a real screenshot in [`docs/screenshots/`](../screenshots/):

1. **Overview** ([02-overview.png](../screenshots/02-overview.png)) — KPI headline numbers and
   time series. Every formula is defined in [`docs/KPI_DICTIONARY.md`](../KPI_DICTIONARY.md).
2. **Work Queue** ([03-work-queue.png](../screenshots/03-work-queue.png)) — SLA-aware backlog,
   filterable and CSV-exportable.
3. **Order Detail** ([04-order-detail.png](../screenshots/04-order-detail.png)) — one order's
   full status timeline, assembled from the events every service emitted.
4. **Incidents** ([05-incidents.png](../screenshots/05-incidents.png)) — the two seeded
   incidents, each with an acknowledge → assign → resolve lifecycle (ADMIN also sees the
   dead-letter replay panel here).
5. **Inventory Risk** ([06-inventory-risk.png](../screenshots/06-inventory-risk.png)) — the SKU
   that crossed its low-stock threshold, surfaced by an `InventoryLowStock.v1` event.
6. **Fulfillment Board** ([07-fulfillment-board.png](../screenshots/07-fulfillment-board.png)) —
   fulfillments by workflow stage.

Talking point: **the order timeline is one story stitched from four independent services'
events** — no service reached into another's database to build it.

## 3. Follow one order across service and Kafka boundaries (optional, ~1 min)

With the observability stack up (`docker compose -f infra/compose/docker-compose.yml up -d
prometheus tempo grafana`), place one order and open its trace in Grafana → Tempo. In Phase 11
this showed **one order as a single 26-span trace across all four services and every Kafka
boundary** (verified against Tempo's API). This is the "single order followed end to end" claim
made concrete.

## 4. Show a failure and its recovery (~1 min)

Run one failure scenario live — see [`FAILURE_DEMO.md`](FAILURE_DEMO.md) for the full narrated
version:

```
tests/failure-scenarios/payment-outage-recovery.sh
```

It drives the payment simulator into repeated technical failures (seeded amount `9998.00`),
watches the circuit breaker trip to **OPEN**, then places a normal order after the wait window
and shows the circuit recover to **CLOSED** with the healthy order authorized. Safe and
reversible — it touches only seeded fictional amounts and never deletes a volume.

## Reset

```
make infra-down            # stops infra; keeps data. Add DOWN_ARGS=-v to wipe volumes.
```

## What a reviewer should take away

- Four independently deployable services coordinating only through **versioned Kafka events**.
- A partial failure is **compensated by choreography** and, when it can't be auto-resolved,
  becomes an **operator incident** with a real lifecycle.
- Every number on screen has a **documented formula**; every claim in the README points to
  code, a test, or generated evidence.
