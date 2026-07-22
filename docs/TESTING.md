# Testing strategy

FulfillOps is tested at four levels, each matched to what it can actually prove:

1. **Unit tests (JUnit 5 + Mockito)** — pure logic: pricing, status-transition tables,
   simulator rules, KPI formulas, authorization mapping, ArchUnit boundary rules.
2. **Web-slice tests (`@WebMvcTest` + Spring Security Test)** — controller authorization and
   request validation without a full context.
3. **Integration tests (Failsafe + Testcontainers)** — the parts that are only real against
   real infrastructure: Flyway migrations, the transactional outbox/inbox, concurrency
   (`SELECT ... FOR UPDATE`, optimistic locking), Kafka retry/DLT routing, the compensation
   saga, and reconciliation — all against real PostgreSQL, Kafka, and Redis containers.
4. **Frontend tests** — Vitest component/unit tests and Playwright end-to-end tests against
   the console's self-contained demo mode.

Contract safety is its own check: the `contracts` module validates every event example
fixture against its JSON Schema, so a schema and its documented example can never drift.

## Commands

```
./mvnw -B test                     # unit + web-slice tests (no Docker needed)
./mvnw -B verify                   # adds Testcontainers integration tests + the coverage gate
./mvnw -B -pl contracts test       # event-contract (schema/example) validation only
make smoke                         # JWT auth end to end against the running stack
make smoke-inventory / -payment / -fulfillment / -cancellation / -operations
cd apps/ops-console && npm test    # Vitest
cd apps/ops-console && npm run e2e # Playwright
make verify-all                    # every feasible local check at once
```

## Current generated counts

These are the numbers a command in this repository actually produced. They are separated by
what was executed versus what is present but was not run in the build environment used for
Phase 13 — see [Honest limitations](#honest-limitations).

**Unit + web-slice tests — executed (`./mvnw -B test`, 0 failures, 0 errors):**

| Module | Tests |
| --- | --- |
| contracts | 14 |
| order-service | 70 |
| inventory-service | 39 |
| payment-service | 30 |
| fulfillment-service | 23 |
| **Total** | **176** |

**Integration tests — present, not executed here:** 40 `*IT.java` files across the four
services (Testcontainers PostgreSQL/Kafka/Redis). Run them with `./mvnw -B verify`. Earlier
phases ran these per service and recorded their pass counts in
[`docs/PHASE_STATUS.md`](PHASE_STATUS.md) (e.g. Phase 11 recorded 269 tests total across the
four services). They were **not** re-run for Phase 13 because this environment's Docker layer
could not run the Testcontainers suite reliably (below).

## Coverage

A JaCoCo gate runs at `verify` on **business code only** — the service, domain, messaging,
and web layers, with generated configuration and DTO records excluded so the number reflects
logic worth testing. Unit and integration coverage are captured by two agents and merged, so
the gate sees both.

The gate is **`coverage.business.line.minimum` = 0.60** (root `pom.xml`), set as a deliberately
conservative floor. Unit tests alone cover 16–26% of business lines here (order 0.219,
inventory 0.262, payment 0.257, fulfillment 0.156 — measured with `./mvnw -B test`); most of
this codebase's coverage comes from the integration tests, which this environment could not
run cleanly. So the true unit+integration coverage figure was **not measured for Phase 13**
and is not claimed — CI's integration job produces it, and the gate is meant to be ratcheted
up to just under that figure once known.

## Performance (k6) — measured, with limits stated

Three k6 scripts under [`tests/perf/`](../tests/perf/) were run against the real local stack
in Phase 11; raw summaries are in [`docs/evidence/k6/`](evidence/k6/). Exactly as measured:

| Scenario | Iterations | Failed requests | p95 latency | Demo target |
| --- | --- | --- | --- | --- |
| Order submission (10 VUs, 30s) | 471 | 0% | 889 ms | 500 ms (missed) |
| Ops work-queue (10 VUs, 30s) | 984 | 0% | 359 ms | 300 ms (missed) |
| Mixed read+write (8+8 VUs, 30s) | 1309 | 0% | 506 ms | 700 ms (met) |

**These are not capacity numbers.** They were produced on a single shared ~7.8 GB sandbox
running Postgres, Kafka, Redis, Keycloak, Prometheus, Grafana, Tempo, all four JVMs, and the
test process at once. Every request succeeded (0% errors); two of three p95 latency targets
were missed under that load. Reported unadjusted. Re-run on a described machine before quoting
any latency figure elsewhere.

## Honest limitations

- The Phase 13 build environment ships only JDK 8/17 (the build enforces JDK 21 — a Temurin 21
  was fetched to build) and its Docker/Testcontainers layer is unreliable under the memory
  ceiling above, so the integration suite, coverage measurement, and image builds were **not**
  re-executed for this phase. What was run (unit + web-slice: 176 tests, 0 failures; contract
  validation; ArchUnit) is reported as executed; everything else is reported as present-and-run
  in an earlier phase or as CI's job, never as freshly measured here.
- No CI run has happened yet — nothing has been pushed — so CI-produced numbers (full coverage,
  green integration/image/scan jobs) are pending, not claimed.
