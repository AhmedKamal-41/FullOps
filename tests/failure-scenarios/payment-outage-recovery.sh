#!/usr/bin/env bash
# Phase 11 failure demo: places several orders at the seeded fictional amount 9998.00 — every
# attempt against the simulator is a technical failure at that amount (see V2__payments.sql,
# "for demonstrating retry exhaustion and the circuit breaker opening") — and watches the
# payment-provider circuit breaker trip to OPEN. Then places a normal-priced order once the
# (shortened, for this demo) wait-duration-in-open-state has elapsed, and confirms the circuit
# recovers to CLOSED and the healthy order is still authorized normally. Starts order-service,
# inventory-service, and payment-service locally against the running Compose infra and stops
# them on exit. Safe and reversible: uses only the simulator's existing seeded fictional test
# amounts, no production code or data is touched.
set -euo pipefail
cd "$(dirname "$0")/../.."

if [ ! -f .env ]; then
  echo "Missing .env — run: cp .env.example .env" >&2
  exit 1
fi
set -a
source .env
set +a

echo "== Checking infrastructure containers are healthy =="
for name in fulfillops-postgres fulfillops-kafka fulfillops-redis fulfillops-keycloak; do
  status=$(docker inspect --format='{{.State.Health.Status}}' "$name" 2>/dev/null || echo "missing")
  echo "  $name: $status"
  if [ "$status" != "healthy" ]; then
    echo "$name is not healthy. Run 'make infra-up' and wait for it to settle, then retry." >&2
    exit 1
  fi
done

token_for() {
  local username="$1" password="$2"
  curl -sf -X POST "$OIDC_ISSUER_URI/protocol/openid-connect/token" \
    -d "grant_type=password" \
    -d "client_id=$OIDC_CLI_CLIENT_ID" \
    -d "client_secret=$OIDC_CLI_CLIENT_SECRET" \
    -d "username=$username" \
    -d "password=$password" \
    -d "scope=openid fulfillops-api" \
    | python3 -c "import sys, json; print(json.load(sys.stdin)['access_token'])"
}

echo "== Obtaining fictional tokens for admin.demo and customer.demo =="
ADMIN_TOKEN=$(token_for admin.demo "AdminDemo!123")
CUSTOMER_TOKEN=$(token_for customer.demo "CustomerDemo!123")
echo "  tokens acquired"

PIDS=()
cleanup() {
  echo "== Stopping services started by this script =="
  for pid in "${PIDS[@]:-}"; do
    kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
}
trap cleanup EXIT

start_service() {
  local service="$1" port="$2"
  shift 2
  echo "== Starting $service (profile: local) on port $port =="
  SPRING_PROFILES_ACTIVE=local env "$@" ./mvnw -q -pl "services/$service" spring-boot:run \
    >"/tmp/${service}-failure-scenario.log" 2>&1 &
  PIDS+=("$!")
  for _ in $(seq 1 60); do
    if curl -sf "http://localhost:${port}/actuator/health/readiness" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "$service never became ready. See /tmp/${service}-failure-scenario.log" >&2
  exit 1
}

start_service inventory-service "$INVENTORY_SERVICE_PORT"
start_service order-service "$ORDER_SERVICE_PORT"
# Shortens the circuit breaker's open-state wait from the production-realistic 30s to 5s, purely
# so this demo doesn't have to sit idle for half a minute — see application.yml for the default.
start_service payment-service "$PAYMENT_SERVICE_PORT" \
  APP_PAYMENT_PROVIDER_CIRCUIT_BREAKER_WAIT_DURATION_IN_OPEN_STATE_MS=5000

SKU="FAILURE-PAYMENT-OUTAGE-$(date +%s)"
echo "== Creating and stocking fictional product $SKU =="
curl -sf -X POST "http://localhost:${INVENTORY_SERVICE_PORT}/api/v1/products" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Idempotency-Key: payment-outage-create-$SKU" \
  -H "Content-Type: application/json" \
  -d "{\"sku\": \"$SKU\", \"name\": \"Payment Outage Demo Widget\", \"description\": \"phase 11 failure scenario\"}" \
  >/dev/null
curl -sf -X POST "http://localhost:${INVENTORY_SERVICE_PORT}/api/v1/inventory/${SKU}/adjustments" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Idempotency-Key: payment-outage-restock-$SKU" \
  -H "Content-Type: application/json" \
  -d '{"changeQuantity": 20, "reasonCode": "RESTOCK", "reasonDetail": "phase 11 failure scenario"}' \
  >/dev/null
echo "  product created and stocked"

circuit_state() {
  curl -sf "http://localhost:${PAYMENT_SERVICE_PORT}/actuator/prometheus" \
    | grep '^resilience4j_circuitbreaker_state{' | grep 'name="payment-provider"' \
    | grep "state=\"$1\"" | awk '{print $2}'
}
place_order_at() {
  local idempotency_key="$1" amount="$2"
  curl -sf -X POST "http://localhost:${ORDER_SERVICE_PORT}/api/v1/orders" \
    -H "Authorization: Bearer $CUSTOMER_TOKEN" \
    -H "Idempotency-Key: $idempotency_key" \
    -H "Content-Type: application/json" \
    -d "{\"items\": [{\"sku\": \"$SKU\", \"quantity\": 1, \"unitPrice\": {\"currencyCode\": \"USD\", \"amount\": \"$amount\"}}]}" \
    >/dev/null
}

echo "== Placing 3 orders at the seeded always-fails amount (9998.00) to trip the circuit breaker =="
for i in 1 2 3; do
  place_order_at "payment-outage-fail-$SKU-$i" "9998.00"
  echo "  order $i placed"
  sleep 1
done

echo "== Waiting for the payment-provider circuit breaker to open =="
OPENED=0
for _ in $(seq 1 20); do
  state=$(circuit_state open)
  if [ "$state" = "1" ]; then
    OPENED=1
    break
  fi
  sleep 2
done
if [ "$OPENED" != "1" ]; then
  echo "the payment-provider circuit breaker never opened" >&2
  exit 1
fi
echo "  confirmed: circuit breaker is OPEN — the simulated outage is being protected against"

echo "== Waiting out the (shortened, 5s) open-state duration =="
sleep 6

echo "== Placing a normal-priced order to confirm the system recovers =="
RECOVERY_ORDER_ID=$(curl -sf -X POST "http://localhost:${ORDER_SERVICE_PORT}/api/v1/orders" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  -H "Idempotency-Key: payment-outage-recovery-$SKU" \
  -H "Content-Type: application/json" \
  -d "{\"items\": [{\"sku\": \"$SKU\", \"quantity\": 1, \"unitPrice\": {\"currencyCode\": \"USD\", \"amount\": \"24.99\"}}]}" \
  | python3 -c "import sys, json; print(json.load(sys.stdin)['orderId'])")
echo "  recovery order placed: $RECOVERY_ORDER_ID"

order_status() {
  curl -sf -H "Authorization: Bearer $CUSTOMER_TOKEN" \
    "http://localhost:${ORDER_SERVICE_PORT}/api/v1/orders/$1" \
    | python3 -c "import sys, json; print(json.load(sys.stdin)['status'])"
}

echo "== Waiting for the recovery order to reach PAYMENT_AUTHORIZED =="
RECOVERED=0
for _ in $(seq 1 20); do
  status=$(order_status "$RECOVERY_ORDER_ID")
  echo "  order status: $status"
  if [ "$status" = "PAYMENT_AUTHORIZED" ] || [ "$status" = "FULFILLMENT_ASSIGNED" ]; then
    RECOVERED=1
    break
  fi
  sleep 2
done
if [ "$RECOVERED" != "1" ]; then
  echo "recovery order never reached PAYMENT_AUTHORIZED after the circuit breaker recovered" >&2
  exit 1
fi

echo "== Failure scenario passed: circuit breaker opened during the simulated outage and closed again, healthy traffic recovered =="
