# Runbook: Payment Technical Failure (Provider Timeout / Circuit Breaker Open)

Covers the payment-provider simulator reporting a technical failure (`TIMEOUT` or
`TEMPORARY_ERROR` — see `SimulatorOutcome` and `V2__payments.sql`) rather than a normal business
decline, and the Resilience4j circuit breaker (`PaymentProviderResilienceConfig`, instance name
`payment-provider`) that protects the rest of the system from it. See
`tests/failure-scenarios/payment-outage-recovery.sh` for a reversible local demo of this exact
scenario end to end, and `docs/adr/0010-payment-simulator-resilience.md` for the resilience design.

## Detection

- **Dashboard**: Grafana → *Inventory / Payment* → "Payment attempt outcomes / min" (watch
  `TIMEOUT`/`TEMPORARY_ERROR`/`CIRCUIT_OPEN` rise) and "Payment provider circuit breaker state".
- **Metrics**: `payment_attempt_outcome_total{outcome="TIMEOUT"|"TEMPORARY_ERROR"|"CIRCUIT_OPEN"}`
  and `resilience4j_circuitbreaker_state{name="payment-provider", state="open"}` (1 when open).
- A technical failure is distinct from a business decline
  (`DECLINE_INSUFFICIENT_FUNDS`/`DECLINE_CARD_DECLINED`) in both the metric's `outcome` label and
  in behavior: a decline never retries (it's a final answer); a technical failure does.

## Impact

While the circuit is **closed** (normal), individual technical failures are retried automatically
(`resilience4j-retry`, up to `app.payment.provider.retry.max-attempts`, exponential backoff) and
are invisible to the customer unless every retry in the budget also fails. Once the circuit is
**open**, every authorization attempt fails immediately with `CallNotPermittedException`
(`CIRCUIT_OPEN` outcome) without even calling the simulator — this protects the system from
spending its retry budget against a provider that's already known to be failing, at the cost of
those orders' payments not being attempted at all until the circuit recovers.

## Diagnosis

1. Confirm the circuit's actual state: `resilience4j_circuitbreaker_state{name="payment-provider"}`
   — exactly one of `closed`/`open`/`half_open`/`disabled`/`forced_open` will read `1`.
2. Check whether failures are concentrated on specific orders/amounts (this simulator's failures
   are deterministic by amount — see `V2__payments.sql`'s seeded fictional test amounts — so a
   real technical-failure spike from genuine traffic would instead be spread across normal
   amounts) versus genuinely broad, which would point at a real regression in
   `SimulatorPaymentProviderAdapter` or its resilience wiring rather than expected demo behavior.
3. Check `payment_attempt` rows for the affected order(s) — every raw attempt is recorded
   independently of the surrounding transaction's outcome (`PaymentAttemptRecorder`,
   `REQUIRES_NEW`), so the full attempt history survives even if authorization ultimately failed.

## Safe action

- **While the circuit is closed and failures are just retrying**: no action needed — this is the
  resilience design working as intended.
- **While the circuit is open**: do not force-retry orders manually; wait for
  `wait-duration-in-open-state-ms` (demo default 30s) to elapse, after which Resilience4j
  automatically probes with `permitted-calls-in-half-open-state` (demo default 3) trial calls and
  closes the circuit again if they succeed. Kafka's own `@RetryableTopic` on the *consumer* side
  (`InventoryReservedListener`) means an order whose authorization attempt was rejected while the
  circuit was open gets a later, less-contended redelivery automatically — nothing needs to be
  manually replayed.
- If the circuit keeps reopening repeatedly rather than recovering, treat it as a real, ongoing
  provider problem, not routine — see the KPI dictionary's technical-failure-rate metric for
  distinguishing "typical simulator noise" from a genuine regression before assuming this is safe
  to ignore.

## Validation

Confirm recovery via the same dashboard panel (circuit state back to `closed`) and by placing (or
observing) a normal-priced order reach `PAYMENT_AUTHORIZED` again. `payment_attempt_outcome_total`
should show `APPROVED`/business-decline outcomes resuming, not just an absence of new failures.

## Escalation

If technical failures are happening at amounts with **no** matching `simulator_rules` row (i.e.
not one of the documented fictional test amounts), this is not expected simulator behavior —
escalate as a code regression in `SimulatorPaymentProviderAdapter` or the resilience configuration,
not a transient "provider" issue.
