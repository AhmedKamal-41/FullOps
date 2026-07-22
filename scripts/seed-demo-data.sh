#!/usr/bin/env bash
# Seed flow: deterministically produces one order of each shape the ops
# console needs to show — happy/delivered, inventory-rejected, payment-declined,
# a low-stock signal, a plain in-flight order, a CANCELLATION_AFTER_DISPATCH
# incident, and a CANCELLATION_STUCK incident — against the real Compose stack.
# Every order/incident is created through the real HTTP APIs, the same choreography
# real traffic goes through; nothing is written directly to a database. Starts all
# four services locally against the running Compose infra and stops them on exit.
#
# The CANCELLATION_STUCK scenario needs order-service's reconciliation job to run
# faster than its 60s/10min production defaults, or this script would need to sit
# idle for over ten minutes. It overrides those two settings via environment
# variables for this one locally-started instance only — same code path, same
# real scheduled job, just tuned for a seed run instead of production pacing.
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

echo "== Obtaining fictional tokens =="
ADMIN_TOKEN=$(token_for admin.demo "AdminDemo!123")
CUSTOMER_TOKEN=$(token_for customer.demo "CustomerDemo!123")
OPERATOR_TOKEN=$(token_for operator.demo "OperatorDemo!123")
echo "  tokens acquired for admin.demo, customer.demo, operator.demo"

PIDS=()
cleanup() {
  echo "== Stopping services started by this script =="
  for pid in "${PIDS[@]:-}"; do
    kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
}
trap cleanup EXIT

LAST_SERVICE_PID=""
start_service() {
  local service="$1" port="$2"
  shift 2
  echo "== Starting $service (profile: local) on port $port =="
  env "$@" SPRING_PROFILES_ACTIVE=local ./mvnw -q -pl "services/$service" spring-boot:run \
    >"/tmp/${service}-seed.log" 2>&1 &
  LAST_SERVICE_PID="$!"
  PIDS+=("$LAST_SERVICE_PID")

  echo "  waiting for /actuator/health/readiness ..."
  for _ in $(seq 1 60); do
    if curl -sf "http://localhost:${port}/actuator/health/readiness" >/dev/null 2>&1; then
      echo "  readiness OK"
      return 0
    fi
    sleep 2
  done
  echo "$service never became ready. See /tmp/${service}-seed.log" >&2
  exit 1
}

start_service inventory-service "$INVENTORY_SERVICE_PORT"
start_service order-service "$ORDER_SERVICE_PORT" \
  APP_RECONCILIATION_INTERVAL_MS=5000 \
  APP_RECONCILIATION_CANCELLATION_STUCK_THRESHOLD=PT8S
start_service payment-service "$PAYMENT_SERVICE_PORT"
PAYMENT_SERVICE_PID="$LAST_SERVICE_PID"
start_service fulfillment-service "$FULFILLMENT_SERVICE_PORT"

RUN_ID=$(date +%s)

create_and_stock_sku() {
  local sku="$1" quantity="$2"
  curl -sf -X POST "http://localhost:${INVENTORY_SERVICE_PORT}/api/v1/products" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Idempotency-Key: seed-create-$sku" \
    -H "Content-Type: application/json" \
    -d "{\"sku\": \"$sku\", \"name\": \"Seed Demo Widget\", \"description\": \"created by scripts/seed-demo-data.sh\"}" \
    >/dev/null
  if [ "$quantity" -gt 0 ]; then
    curl -sf -X POST "http://localhost:${INVENTORY_SERVICE_PORT}/api/v1/inventory/${sku}/adjustments" \
      -H "Authorization: Bearer $ADMIN_TOKEN" \
      -H "Idempotency-Key: seed-restock-$sku" \
      -H "Content-Type: application/json" \
      -d "{\"changeQuantity\": $quantity, \"reasonCode\": \"RESTOCK\", \"reasonDetail\": \"seed demo stock\"}" \
      >/dev/null
  fi
}

place_order() {
  local idempotency_key="$1" sku="$2" quantity="$3" unit_price="$4"
  curl -sf -X POST "http://localhost:${ORDER_SERVICE_PORT}/api/v1/orders" \
    -H "Authorization: Bearer $CUSTOMER_TOKEN" \
    -H "Idempotency-Key: $idempotency_key" \
    -H "Content-Type: application/json" \
    -d "{\"items\": [{\"sku\": \"$sku\", \"quantity\": $quantity, \"unitPrice\": {\"currencyCode\": \"USD\", \"amount\": \"$unit_price\"}}]}" \
    | python3 -c "import sys, json; print(json.load(sys.stdin)['orderId'])"
}

order_status() {
  curl -sf "http://localhost:${ORDER_SERVICE_PORT}/api/v1/orders/${1}" \
    -H "Authorization: Bearer $CUSTOMER_TOKEN" \
    | python3 -c "import sys, json; print(json.load(sys.stdin)['status'])"
}

wait_for_status() {
  local order_id="$1" timeout_attempts="$2"
  shift 2
  local wanted=("$@")
  local status=""
  for _ in $(seq 1 "$timeout_attempts"); do
    status=$(order_status "$order_id")
    for w in "${wanted[@]}"; do
      [ "$status" = "$w" ] && { echo "$status"; return 0; }
    done
    sleep 2
  done
  echo "order $order_id never reached any of [${wanted[*]}] (stuck at $status)" >&2
  return 1
}

fulfillment_for_order() {
  local order_id="$1" status="$2"
  curl -sf "http://localhost:${FULFILLMENT_SERVICE_PORT}/api/v1/fulfillments?status=${status}" \
    -H "Authorization: Bearer $OPERATOR_TOKEN" \
    | python3 -c "
import sys, json
body = json.load(sys.stdin)
match = [f for f in body['content'] if f['orderId'] == '$order_id']
print(json.dumps(match[0]) if match else '')
"
}

wait_for_fulfillment() {
  local order_id="$1"
  local found=""
  for _ in $(seq 1 20); do
    found=$(fulfillment_for_order "$order_id" "ASSIGNED")
    [ -n "$found" ] && { echo "$found"; return 0; }
    sleep 2
  done
  echo "no ASSIGNED fulfillment appeared for order $order_id" >&2
  return 1
}

field() {
  python3 -c "import sys, json; print(json.load(sys.stdin)['$2'])" <<<"$1"
}

claim_fulfillment() {
  local fulfillment_id="$1" version="$2"
  curl -sf -X POST "http://localhost:${FULFILLMENT_SERVICE_PORT}/api/v1/fulfillments/${fulfillment_id}/claim" \
    -H "Authorization: Bearer $OPERATOR_TOKEN" \
    -H "If-Match: $version"
}

advance_fulfillment() {
  local fulfillment_id="$1" version="$2" new_status="$3" extra_json="${4:-}"
  local body="{\"newStatus\": \"$new_status\"$extra_json}"
  curl -sf -X PATCH "http://localhost:${FULFILLMENT_SERVICE_PORT}/api/v1/fulfillments/${fulfillment_id}/status" \
    -H "Authorization: Bearer $OPERATOR_TOKEN" \
    -H "If-Match: $version" \
    -H "Content-Type: application/json" \
    -d "$body"
}

echo
echo "########## Scenario 1: happy path, fully delivered ##########"
SKU_HAPPY="SEED-HAPPY-$RUN_ID"
create_and_stock_sku "$SKU_HAPPY" 10
HAPPY_ORDER_ID=$(place_order "seed-happy-$RUN_ID" "$SKU_HAPPY" 1 "24.99")
echo "  order placed: $HAPPY_ORDER_ID"
wait_for_status "$HAPPY_ORDER_ID" 20 FULFILLMENT_ASSIGNED PICKING >/dev/null
FULFILLMENT=$(wait_for_fulfillment "$HAPPY_ORDER_ID")
FID=$(field "$FULFILLMENT" fulfillmentId)
VERSION=$(field "$FULFILLMENT" version)
echo "  claiming fulfillment $FID"
RESP=$(claim_fulfillment "$FID" "$VERSION")
VERSION=$(field "$RESP" version)
for step in PICKING PACKED; do
  echo "  advancing to $step"
  RESP=$(advance_fulfillment "$FID" "$VERSION" "$step")
  VERSION=$(field "$RESP" version)
done
echo "  advancing to DISPATCHED"
RESP=$(advance_fulfillment "$FID" "$VERSION" "DISPATCHED" ", \"trackingReference\": \"SEED-TRACK-$RUN_ID\"")
VERSION=$(field "$RESP" version)
echo "  advancing to DELIVERED"
DELIVERED_AT=$(python3 -c "import datetime; print(datetime.datetime.now(datetime.timezone.utc).isoformat())")
advance_fulfillment "$FID" "$VERSION" "DELIVERED" ", \"deliveredAt\": \"$DELIVERED_AT\"" >/dev/null
echo "  order $HAPPY_ORDER_ID delivered"

echo
echo "########## Scenario 2: inventory rejected (out of stock) ##########"
SKU_REJECTED="SEED-REJECTED-$RUN_ID"
create_and_stock_sku "$SKU_REJECTED" 0
REJECTED_ORDER_ID=$(place_order "seed-rejected-$RUN_ID" "$SKU_REJECTED" 1 "15.00")
echo "  order placed: $REJECTED_ORDER_ID"
wait_for_status "$REJECTED_ORDER_ID" 15 CANCELLED >/dev/null
echo "  order $REJECTED_ORDER_ID cancelled (inventory rejected)"

echo
echo "########## Scenario 3: payment declined, auto-cancelled ##########"
SKU_DECLINE="SEED-DECLINE-$RUN_ID"
create_and_stock_sku "$SKU_DECLINE" 5
DECLINE_ORDER_ID=$(place_order "seed-decline-$RUN_ID" "$SKU_DECLINE" 1 "1.00")
echo "  order placed: $DECLINE_ORDER_ID"
wait_for_status "$DECLINE_ORDER_ID" 15 CANCELLED >/dev/null
echo "  order $DECLINE_ORDER_ID cancelled (payment declined)"

echo
echo "########## Scenario 4: low-stock signal, order still succeeds ##########"
SKU_LOWSTOCK="SEED-LOWSTOCK-$RUN_ID"
create_and_stock_sku "$SKU_LOWSTOCK" 12
LOWSTOCK_ORDER_ID=$(place_order "seed-lowstock-$RUN_ID" "$SKU_LOWSTOCK" 5 "9.99")
echo "  order placed: $LOWSTOCK_ORDER_ID (12 in stock, 5 ordered, crosses the default threshold of 10)"
wait_for_status "$LOWSTOCK_ORDER_ID" 15 FULFILLMENT_ASSIGNED PICKING PACKED DISPATCHED >/dev/null
echo "  order $LOWSTOCK_ORDER_ID progressed normally; low-stock signal recorded for $SKU_LOWSTOCK"

echo
echo "########## Scenario 5: plain in-flight order (left untouched) ##########"
SKU_INFLIGHT="SEED-INFLIGHT-$RUN_ID"
create_and_stock_sku "$SKU_INFLIGHT" 10
INFLIGHT_ORDER_ID=$(place_order "seed-inflight-$RUN_ID" "$SKU_INFLIGHT" 1 "12.50")
echo "  order placed: $INFLIGHT_ORDER_ID"
wait_for_status "$INFLIGHT_ORDER_ID" 15 FULFILLMENT_ASSIGNED >/dev/null
echo "  order $INFLIGHT_ORDER_ID left ASSIGNED — no fulfillment action taken"

echo
echo "########## Scenario 6: cancellation after dispatch (incident) ##########"
SKU_DISPATCH_CANCEL="SEED-DISPATCHCANCEL-$RUN_ID"
create_and_stock_sku "$SKU_DISPATCH_CANCEL" 10
DISPATCH_CANCEL_ORDER_ID=$(place_order "seed-dispatchcancel-$RUN_ID" "$SKU_DISPATCH_CANCEL" 1 "42.00")
echo "  order placed: $DISPATCH_CANCEL_ORDER_ID"
wait_for_status "$DISPATCH_CANCEL_ORDER_ID" 20 FULFILLMENT_ASSIGNED PICKING >/dev/null
FULFILLMENT=$(wait_for_fulfillment "$DISPATCH_CANCEL_ORDER_ID")
FID=$(field "$FULFILLMENT" fulfillmentId)
VERSION=$(field "$FULFILLMENT" version)
RESP=$(claim_fulfillment "$FID" "$VERSION")
VERSION=$(field "$RESP" version)
for step in PICKING PACKED; do
  RESP=$(advance_fulfillment "$FID" "$VERSION" "$step")
  VERSION=$(field "$RESP" version)
done
advance_fulfillment "$FID" "$VERSION" "DISPATCHED" ", \"trackingReference\": \"SEED-TRACK-DC-$RUN_ID\"" >/dev/null
wait_for_status "$DISPATCH_CANCEL_ORDER_ID" 10 DISPATCHED >/dev/null
echo "  order $DISPATCH_CANCEL_ORDER_ID reached DISPATCHED, now cancelling as admin.demo"
curl -sf -X POST "http://localhost:${ORDER_SERVICE_PORT}/api/v1/orders/${DISPATCH_CANCEL_ORDER_ID}/cancellation-requests" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Idempotency-Key: seed-dispatchcancel-request-$RUN_ID" \
  -H "Content-Type: application/json" \
  -d '{"reasonDetail": "customer changed their mind after dispatch (seed demo)"}' \
  >/dev/null
echo "  cancellation requested — CANCELLATION_AFTER_DISPATCH incident expected"

echo
echo "########## Scenario 7: stuck cancellation (incident, needs payment-service down) ##########"
SKU_STUCK="SEED-STUCK-$RUN_ID"
create_and_stock_sku "$SKU_STUCK" 10
STUCK_ORDER_ID=$(place_order "seed-stuck-$RUN_ID" "$SKU_STUCK" 1 "30.00")
echo "  order placed: $STUCK_ORDER_ID"
wait_for_status "$STUCK_ORDER_ID" 15 PAYMENT_AUTHORIZED FULFILLMENT_ASSIGNED >/dev/null
echo "  order authorized; stopping payment-service so its refund can never be consumed"
kill "$PAYMENT_SERVICE_PID" 2>/dev/null || true
wait "$PAYMENT_SERVICE_PID" 2>/dev/null || true
echo "  payment-service stopped"
curl -sf -X POST "http://localhost:${ORDER_SERVICE_PORT}/api/v1/orders/${STUCK_ORDER_ID}/cancellation-requests" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  -H "Idempotency-Key: seed-stuck-request-$RUN_ID" \
  -H "Content-Type: application/json" \
  -d '{"reasonDetail": "seed demo — intentionally left stuck"}' \
  >/dev/null
echo "  cancellation requested; waiting for the reconciliation job to open a CANCELLATION_STUCK incident"
FOUND_STUCK=""
for _ in $(seq 1 15); do
  FOUND_STUCK=$(curl -sf "http://localhost:${ORDER_SERVICE_PORT}/api/v1/ops/incidents?kind=CANCELLATION_STUCK&orderId=${STUCK_ORDER_ID}" \
    -H "Authorization: Bearer $OPERATOR_TOKEN" \
    | python3 -c "import sys, json; print('yes' if json.load(sys.stdin)['content'] else '')")
  [ -n "$FOUND_STUCK" ] && break
  sleep 2
done
if [ -z "$FOUND_STUCK" ]; then
  echo "no CANCELLATION_STUCK incident appeared for order $STUCK_ORDER_ID within the timeout" >&2
  exit 1
fi
echo "  CANCELLATION_STUCK incident confirmed for order $STUCK_ORDER_ID"

echo "== Restarting payment-service so the stack is left fully healthy =="
start_service payment-service "$PAYMENT_SERVICE_PORT"

cat <<SUMMARY

########## Seed complete ##########
Happy path (delivered):        $HAPPY_ORDER_ID
Inventory rejected:            $REJECTED_ORDER_ID
Payment declined:              $DECLINE_ORDER_ID
Low-stock signal ($SKU_LOWSTOCK): $LOWSTOCK_ORDER_ID
Plain in-flight order:         $INFLIGHT_ORDER_ID
Cancellation after dispatch:   $DISPATCH_CANCEL_ORDER_ID
Stuck cancellation:            $STUCK_ORDER_ID
SUMMARY
