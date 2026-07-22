#!/usr/bin/env bash
# Failure demo: starts order-service and inventory-service only — deliberately leaving
# payment-service down — so a placed order reserves stock and then has nowhere to go, stuck in
# INVENTORY_RESERVED. Runs order-service with a drastically shortened reconciliation interval and
# stuck-threshold (seconds instead of the production-realistic 60s/30m — see application.yml) so
# this demo doesn't have to wait half an hour, and confirms reconciliation opens an incident for
# the stuck order. Then starts payment-service (and fulfillment-service) to show the order
# recovers on its own once the missing piece comes back. Stops every service it started on exit.
# Safe and reversible: only ever appends rows, nothing is deleted.
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
# Shortens reconciliation from the production-realistic 60s interval / 30m stuck-threshold down
# to seconds, purely so this demo doesn't have to sit idle for half an hour — see application.yml.
start_service order-service "$ORDER_SERVICE_PORT" \
  APP_RECONCILIATION_INTERVAL_MS=5000 \
  APP_RECONCILIATION_STUCK_THRESHOLD=PT10S

SKU="FAILURE-STUCK-ORDER-$(date +%s)"
echo "== Creating and stocking fictional product $SKU (payment-service deliberately not started) =="
curl -sf -X POST "http://localhost:${INVENTORY_SERVICE_PORT}/api/v1/products" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Idempotency-Key: stuck-create-$SKU" \
  -H "Content-Type: application/json" \
  -d "{\"sku\": \"$SKU\", \"name\": \"Stuck Order Demo Widget\", \"description\": \"phase 11 failure scenario\"}" \
  >/dev/null
curl -sf -X POST "http://localhost:${INVENTORY_SERVICE_PORT}/api/v1/inventory/${SKU}/adjustments" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Idempotency-Key: stuck-restock-$SKU" \
  -H "Content-Type: application/json" \
  -d '{"changeQuantity": 5, "reasonCode": "RESTOCK", "reasonDetail": "phase 11 failure scenario"}' \
  >/dev/null

echo "== Placing an order that can only ever reach INVENTORY_RESERVED =="
ORDER_ID=$(curl -sf -X POST "http://localhost:${ORDER_SERVICE_PORT}/api/v1/orders" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  -H "Idempotency-Key: stuck-order-$SKU" \
  -H "Content-Type: application/json" \
  -d "{\"items\": [{\"sku\": \"$SKU\", \"quantity\": 1, \"unitPrice\": {\"currencyCode\": \"USD\", \"amount\": \"12.50\"}}]}" \
  | python3 -c "import sys, json; print(json.load(sys.stdin)['orderId'])")
echo "  order placed: $ORDER_ID"

order_status() {
  curl -sf -H "Authorization: Bearer $CUSTOMER_TOKEN" \
    "http://localhost:${ORDER_SERVICE_PORT}/api/v1/orders/$1" \
    | python3 -c "import sys, json; print(json.load(sys.stdin)['status'])"
}
incident_open_for_order() {
  curl -sf -H "Authorization: Bearer $ADMIN_TOKEN" \
    "http://localhost:${ORDER_SERVICE_PORT}/api/v1/ops/incidents?orderId=$1&status=OPEN" \
    | python3 -c "import sys, json; print(json.load(sys.stdin)['totalElements'])"
}

echo "== Waiting for the order to reach INVENTORY_RESERVED (its dead end without payment-service) =="
for _ in $(seq 1 20); do
  status=$(order_status "$ORDER_ID")
  if [ "$status" = "INVENTORY_RESERVED" ]; then
    break
  fi
  sleep 2
done
echo "  order status: $(order_status "$ORDER_ID")"

echo "== Waiting for reconciliation to detect it as stuck and open an incident =="
DETECTED=0
for _ in $(seq 1 20); do
  count=$(incident_open_for_order "$ORDER_ID")
  if [ "$count" -gt 0 ]; then
    DETECTED=1
    break
  fi
  sleep 3
done
if [ "$DETECTED" != "1" ]; then
  echo "no open incident was ever created for the stuck order $ORDER_ID" >&2
  exit 1
fi
echo "  confirmed: an OPEN incident exists for order $ORDER_ID"

echo "== Starting payment-service and fulfillment-service to demonstrate recovery =="
start_service payment-service "$PAYMENT_SERVICE_PORT"
start_service fulfillment-service "$FULFILLMENT_SERVICE_PORT"

echo "== Waiting for the previously-stuck order to make real progress now that payment-service is available =="
RECOVERED=0
for _ in $(seq 1 30); do
  status=$(order_status "$ORDER_ID")
  echo "  order status: $status"
  if [ "$status" = "PAYMENT_AUTHORIZED" ] || [ "$status" = "FULFILLMENT_ASSIGNED" ]; then
    RECOVERED=1
    break
  fi
  sleep 2
done
if [ "$RECOVERED" != "1" ]; then
  echo "the stuck order never progressed past INVENTORY_RESERVED after payment-service started" >&2
  exit 1
fi

echo "== Failure scenario passed: reconciliation detected the stuck order, and it recovered once the missing service returned =="
