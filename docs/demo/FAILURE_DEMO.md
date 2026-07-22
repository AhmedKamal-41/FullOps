# Failure demo — detect, diagnose, recover, validate

This is the reliability story told as an operator would live it: something breaks, an alert
fires, you diagnose it from metrics and traces, it recovers, and you confirm the recovery. Every
scenario is a committed script under [`tests/failure-scenarios/`](../../tests/failure-scenarios/)
that uses only the system's real behavior and seeded fictional amounts — no manual database
edits, and no volumes are ever deleted.

## Prerequisites

```
cp .env.example .env
make infra-up
# For the alert/metrics/trace views:
docker compose -f infra/compose/docker-compose.yml up -d prometheus tempo grafana
```

- Grafana: http://localhost:3000 (anonymous viewer enabled) — dashboards **System Health**,
  **Order Lifecycle**, **Inventory/Payment**, **Fulfillment Operations**, **Messaging
  Reliability**.
- Prometheus alert rules: [`infra/compose/observability/prometheus/rules/fulfillops-alerts.yml`](../../infra/compose/observability/prometheus/rules/fulfillops-alerts.yml)
  (six demo-labeled rules).

## Primary scenario: payment provider outage and recovery

```
tests/failure-scenarios/payment-outage-recovery.sh
```

| Phase | What happens | Where you see it |
| --- | --- | --- |
| **Failure** | Several orders at the seeded amount `9998.00` — every attempt is a technical failure — drive the payment-provider circuit breaker to **OPEN**. | `payment.attempt.outcome` and the Resilience4j circuit-state metric on the **Inventory/Payment** dashboard; the script logs the state change. |
| **Alert** | Sustained failures raise the demo error-rate / circuit alert. | Prometheus → Alerts (rules are demo defaults, labeled as such). |
| **Diagnosis** | The circuit is OPEN because the provider is failing fast, not because orders are malformed. Traces for the failing orders show the retries and the fail-fast. | Grafana → Tempo trace of a failing order; the payment attempt history via `GET /api/v1/payments/{id}/attempts`. |
| **Recovery** | After the (shortened, for the demo) open-state wait window, a normal-priced order is placed; the circuit goes **HALF-OPEN** then **CLOSED**. | The script logs the transition; the circuit-state metric returns to closed. |
| **Validation** | The healthy order is authorized normally end to end. | `PaymentAuthorized.v1` on `fulfillops.payment.events`; the order advances in the console. |

## Other committed scenarios

Each follows the same detect → diagnose → recover → validate shape:

| Script | Demonstrates |
| --- | --- |
| [`kafka-outage-outbox-backlog.sh`](../../tests/failure-scenarios/kafka-outage-outbox-backlog.sh) | Stopping Kafka makes the outbox backlog metric climb, then drain to zero on recovery. |
| [`redis-outage-fallback.sh`](../../tests/failure-scenarios/redis-outage-fallback.sh) | Redis down → the availability cache fails open to PostgreSQL; reads still return correct data. |
| [`duplicate-kafka-delivery.sh`](../../tests/failure-scenarios/duplicate-kafka-delivery.sh) | A redelivered event is a no-op (inbox dedup) — no double reservation/payment. |
| [`poison-message-dlt.sh`](../../tests/failure-scenarios/poison-message-dlt.sh) | A non-retryable payload is routed straight to the dead-letter topic, then replayed by the audited ADMIN endpoint. |
| [`stuck-order-reconciliation.sh`](../../tests/failure-scenarios/stuck-order-reconciliation.sh) | A stuck order is found by the reconciliation job and either nudged or escalated to a `REQUIRES_REVIEW` incident. |

## Runbooks

Each incident type has an operator runbook with detection, impact, diagnosis, safe action,
validation, and escalation:

- [DLT growth](../runbooks/dlt-growth.md)
- [Stuck orders](../runbooks/stuck-orders.md)
- [Payment technical failure](../runbooks/payment-technical-failure.md)
- [Inventory contention](../runbooks/inventory-contention.md)
- [Outbox backlog](../runbooks/outbox-backlog.md)
- [Incident lifecycle](../runbooks/INCIDENT_MANAGEMENT.md)

## Screenshot capture (not committed in this repo)

Sanitized Grafana, trace, and failure-recovery screenshots are **not** included — capturing them
requires running the full observability stack, which the Phase 13 build environment could not do
reliably (see [`docs/KNOWN_LIMITATIONS.md`](../KNOWN_LIMITATIONS.md)). To capture them yourself:
bring up the stack as above, run a scenario, and screenshot (1) the relevant Grafana dashboard
during the failure, (2) the Tempo trace of one affected order, and (3) the dashboard after
recovery. Save them under `docs/screenshots/` as `08-grafana-*.png`, `09-trace-*.png`,
`10-recovery-*.png`.
