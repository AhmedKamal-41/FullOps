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

These are the numbers recorded by a command in this repository. Re-run the commands above for
the current result; test counts can change as the project evolves.

**Unit + web-slice tests — executed (`./mvnw -B test`, 0 failures, 0 errors):**

| Module | Tests |
| --- | --- |
| contracts | 14 |
| order-service | 70 |
| inventory-service | 39 |
| payment-service | 30 |
| fulfillment-service | 23 |
| **Total** | **176** |

**Integration tests:** 40 `*IT.java` files across the four services use Testcontainers with
PostgreSQL, Kafka, and Redis. Run the complete unit and integration suite with `./mvnw -B verify`.
Docker must be available for the Testcontainers portion of the build.

## Coverage

A JaCoCo gate runs at `verify` on **business code only** — the service, domain, messaging,
and web layers, with generated configuration and DTO records excluded so the number reflects
logic worth testing. Unit and integration coverage are captured by two agents and merged, so
the gate sees both.

The gate is **`coverage.business.line.minimum` = 0.60** (root `pom.xml`), set as a deliberately
conservative floor. Unit tests alone cover 16–26% of business lines here (order 0.219,
inventory 0.262, payment 0.257, fulfillment 0.156 — measured with `./mvnw -B test`); most of
this codebase's coverage comes from the integration tests. The combined figure is produced by
`./mvnw -B verify` and CI rather than inferred from the unit-only measurements above. The gate
should be ratcheted only from a stable, reproducible combined baseline.

## Performance (k6) — measured, with limits stated

Three k6 scripts under [`tests/perf/`](../tests/perf/) were run against the real local stack;
raw summaries are in [`docs/evidence/k6/`](evidence/k6/). Exactly as measured:

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

- The full verification path requires JDK 21 and a Docker environment capable of running
  PostgreSQL, Kafka, and Redis Testcontainers. The 176-test unit/web-slice count above does not
  imply that the integration suite ran in the same command.
- Recorded test and k6 results are evidence from specific runs, not permanent claims. Use the
  documented commands and CI history for current results.
