#!/usr/bin/env bash
# Phase 6 smoke flow: creates and stocks a fictional product, places two real
# orders through Order Service — one at a normal price (no matching
# simulator_rules row, so it approves) and one at the seeded fictional decline
# amount (1.00) — and observes the resulting PaymentAuthorized.v1/
# PaymentDeclined.v1 events actually land on fulfillops.payment.events. Starts
# only the three services it needs (order, inventory, payment) locally against
# the running Compose infra, and stops them on exit.
set -euo pipefail
cd "$(dirname "$0")/.."

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
  echo "== Starting $service (profile: local) on port $port =="
  SPRING_PROFILES_ACTIVE=local ./mvnw -q -pl "services/$service" spring-boot:run \
    >"/tmp/${service}-smoke.log" 2>&1 &
  PIDS+=("$!")

  echo "  waiting for /actuator/health/readiness ..."
  for _ in $(seq 1 60); do
    if curl -sf "http://localhost:${port}/actuator/health/readiness" >/dev/null 2>&1; then
      echo "  readiness OK"
      return 0
    fi
    sleep 2
  done
  echo "$service never became ready. See /tmp/${service}-smoke.log" >&2
  exit 1
}

start_service inventory-service "$INVENTORY_SERVICE_PORT"
start_service order-service "$ORDER_SERVICE_PORT"
start_service payment-service "$PAYMENT_SERVICE_PORT"

SKU="SMOKE-PAYMENT-WIDGET-$(date +%s)"
echo "== Creating fictional product $SKU =="
curl -sf -X POST "http://localhost:${INVENTORY_SERVICE_PORT}/api/v1/products" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Idempotency-Key: smoke-create-$SKU" \
  -H "Content-Type: application/json" \
  -d "{\"sku\": \"$SKU\", \"name\": \"Smoke Test Widget\", \"description\": \"created by scripts/smoke-payment-authorization.sh\"}" \
  >/dev/null
echo "  product created"

echo "== Stocking $SKU with 10 units =="
curl -sf -X POST "http://localhost:${INVENTORY_SERVICE_PORT}/api/v1/inventory/${SKU}/adjustments" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Idempotency-Key: smoke-restock-$SKU" \
  -H "Content-Type: application/json" \
  -d '{"changeQuantity": 10, "reasonCode": "RESTOCK", "reasonDetail": "smoke test seed stock"}' \
  >/dev/null
echo "  stock adjusted"

place_order() {
  local idempotency_key="$1" unit_price="$2"
  curl -sf -X POST "http://localhost:${ORDER_SERVICE_PORT}/api/v1/orders" \
    -H "Authorization: Bearer $CUSTOMER_TOKEN" \
    -H "Idempotency-Key: $idempotency_key" \
    -H "Content-Type: application/json" \
    -d "{\"items\": [{\"sku\": \"$SKU\", \"quantity\": 1, \"unitPrice\": {\"currencyCode\": \"USD\", \"amount\": \"$unit_price\"}}]}" \
    | python3 -c "import sys, json; print(json.load(sys.stdin)['orderId'])"
}

wait_for_payment_event() {
  local order_id="$1"
  local found=""
  for _ in $(seq 1 20); do
    output=$(docker exec fulfillops-kafka /opt/kafka/bin/kafka-console-consumer.sh \
      --bootstrap-server localhost:9092 \
      --topic fulfillops.payment.events \
      --from-beginning \
      --timeout-ms 3000 2>/dev/null || true)
    line=$(echo "$output" | grep "\"aggregateId\":\"$order_id\"" || true)
    if [ -n "$line" ]; then
      found="$line"
      break
    fi
    sleep 2
  done
  if [ -z "$found" ]; then
    echo "no payment event observed for order $order_id within the timeout" >&2
    exit 1
  fi
  echo "$found" | python3 -c "import sys, json; print(json.load(sys.stdin)['eventType'])"
}

echo "== Placing an order at a normal price (no matching simulator_rules row) =="
APPROVE_ORDER_ID=$(place_order "smoke-approve-$SKU" "19.99")
echo "  order placed: $APPROVE_ORDER_ID"
echo "== Waiting for a payment event for order $APPROVE_ORDER_ID =="
APPROVE_EVENT_TYPE=$(wait_for_payment_event "$APPROVE_ORDER_ID")
echo "  observed $APPROVE_EVENT_TYPE for order $APPROVE_ORDER_ID"
if [ "$APPROVE_EVENT_TYPE" != "PaymentAuthorized" ]; then
  echo "expected PaymentAuthorized for a normal-priced order, got $APPROVE_EVENT_TYPE" >&2
  exit 1
fi

echo "== Placing an order at the seeded fictional decline amount (1.00) =="
DECLINE_ORDER_ID=$(place_order "smoke-decline-$SKU" "1.00")
echo "  order placed: $DECLINE_ORDER_ID"
echo "== Waiting for a payment event for order $DECLINE_ORDER_ID =="
DECLINE_EVENT_TYPE=$(wait_for_payment_event "$DECLINE_ORDER_ID")
echo "  observed $DECLINE_EVENT_TYPE for order $DECLINE_ORDER_ID"
if [ "$DECLINE_EVENT_TYPE" != "PaymentDeclined" ]; then
  echo "expected PaymentDeclined for the seeded decline amount, got $DECLINE_EVENT_TYPE" >&2
  exit 1
fi

echo "== Smoke test passed: one order approved, one order declined, both events observed on Kafka =="
