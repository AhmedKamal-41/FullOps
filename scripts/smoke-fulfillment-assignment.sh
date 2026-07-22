#!/usr/bin/env bash
# Smoke flow: creates and stocks a fictional product, places one real
# order at a normal price through Order Service, and follows it all the way to
# a real FulfillmentAssigned.v1 event on fulfillops.fulfillment.events — the
# full payment-authorization-to-assigned-fulfillment path. Starts all four
# services locally against the running Compose infra and stops them on exit.
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
start_service fulfillment-service "$FULFILLMENT_SERVICE_PORT"

SKU="SMOKE-FULFILLMENT-WIDGET-$(date +%s)"
echo "== Creating fictional product $SKU =="
curl -sf -X POST "http://localhost:${INVENTORY_SERVICE_PORT}/api/v1/products" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Idempotency-Key: smoke-create-$SKU" \
  -H "Content-Type: application/json" \
  -d "{\"sku\": \"$SKU\", \"name\": \"Smoke Test Widget\", \"description\": \"created by scripts/smoke-fulfillment-assignment.sh\"}" \
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

echo "== Placing an order at a normal price (no matching simulator_rules row) =="
ORDER_ID=$(curl -sf -X POST "http://localhost:${ORDER_SERVICE_PORT}/api/v1/orders" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  -H "Idempotency-Key: smoke-fulfillment-$SKU" \
  -H "Content-Type: application/json" \
  -d "{\"items\": [{\"sku\": \"$SKU\", \"quantity\": 1, \"unitPrice\": {\"currencyCode\": \"USD\", \"amount\": \"19.99\"}}]}" \
  | python3 -c "import sys, json; print(json.load(sys.stdin)['orderId'])")
echo "  order placed: $ORDER_ID"

echo "== Waiting for a FulfillmentAssigned.v1 event for order $ORDER_ID =="
FOUND=""
for _ in $(seq 1 20); do
  output=$(docker exec fulfillops-kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 \
    --topic fulfillops.fulfillment.events \
    --from-beginning \
    --timeout-ms 3000 2>/dev/null || true)
  line=$(echo "$output" | grep "\"aggregateId\":\"$ORDER_ID\"" || true)
  if [ -n "$line" ]; then
    FOUND="$line"
    break
  fi
  sleep 2
done
if [ -z "$FOUND" ]; then
  echo "no fulfillment event observed for order $ORDER_ID within the timeout" >&2
  exit 1
fi
EVENT_TYPE=$(echo "$FOUND" | python3 -c "import sys, json; print(json.load(sys.stdin)['eventType'])")
echo "  observed $EVENT_TYPE for order $ORDER_ID"
if [ "$EVENT_TYPE" != "FulfillmentAssigned" ]; then
  echo "expected FulfillmentAssigned for a normally-paid order, got $EVENT_TYPE" >&2
  exit 1
fi

echo "== Confirming the operator work-queue API lists the new fulfillment as ASSIGNED =="
LISTED_STATUS=$(curl -sf "http://localhost:${FULFILLMENT_SERVICE_PORT}/api/v1/fulfillments?status=ASSIGNED" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  | python3 -c "
import sys, json
body = json.load(sys.stdin)
match = [f for f in body['content'] if f['orderId'] == '$ORDER_ID']
print(match[0]['status'] if match else 'NOT_FOUND')
")
echo "  fulfillment status via API: $LISTED_STATUS"
if [ "$LISTED_STATUS" != "ASSIGNED" ]; then
  echo "expected the new fulfillment to be listed as ASSIGNED, got $LISTED_STATUS" >&2
  exit 1
fi

echo "== Smoke test passed: order paid, fulfillment auto-assigned, and visible via the operator API =="
