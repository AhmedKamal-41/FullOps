#!/usr/bin/env bash
# Phase 11 failure demo: places one real order, captures the exact OrderPlaced.v1 JSON
# inventory-service already consumed from fulfillops.order.events, then republishes that exact
# same message (same eventId) straight onto the topic — simulating the at-least-once redelivery
# Kafka can always do. Confirms inventory-service's inbox dedup silently ignores it (the
# kafka.consumer.duplicate counter increments, no second reservation is created) rather than
# double-reserving stock. Starts order-service and inventory-service locally against the running
# Compose infra and stops them on exit. Safe and reversible: only ever appends messages to Kafka
# and rows to Postgres, nothing is deleted.
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
  echo "== Starting $service (profile: local) on port $port =="
  SPRING_PROFILES_ACTIVE=local ./mvnw -q -pl "services/$service" spring-boot:run \
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

SKU="FAILURE-DUPLICATE-$(date +%s)"
echo "== Creating and stocking fictional product $SKU =="
curl -sf -X POST "http://localhost:${INVENTORY_SERVICE_PORT}/api/v1/products" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Idempotency-Key: duplicate-create-$SKU" \
  -H "Content-Type: application/json" \
  -d "{\"sku\": \"$SKU\", \"name\": \"Duplicate Delivery Demo Widget\", \"description\": \"phase 11 failure scenario\"}" \
  >/dev/null
curl -sf -X POST "http://localhost:${INVENTORY_SERVICE_PORT}/api/v1/inventory/${SKU}/adjustments" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Idempotency-Key: duplicate-restock-$SKU" \
  -H "Content-Type: application/json" \
  -d '{"changeQuantity": 10, "reasonCode": "RESTOCK", "reasonDetail": "phase 11 failure scenario"}' \
  >/dev/null
echo "  product created and stocked"

echo "== Placing one real order =="
ORDER_ID=$(curl -sf -X POST "http://localhost:${ORDER_SERVICE_PORT}/api/v1/orders" \
  -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  -H "Idempotency-Key: duplicate-order-$SKU" \
  -H "Content-Type: application/json" \
  -d "{\"items\": [{\"sku\": \"$SKU\", \"quantity\": 2, \"unitPrice\": {\"currencyCode\": \"USD\", \"amount\": \"14.99\"}}]}" \
  | python3 -c "import sys, json; print(json.load(sys.stdin)['orderId'])")
echo "  order placed: $ORDER_ID"

reservation_count() {
  docker exec fulfillops-postgres psql -U "$POSTGRES_SUPERUSER" -d inventory_db -tAc \
    "SELECT count(*) FROM inventory_reservation WHERE order_id = '$ORDER_ID'"
}

echo "== Waiting for the original reservation to land =="
RESERVED=0
for _ in $(seq 1 20); do
  count=$(reservation_count)
  if [ "$count" = "1" ]; then
    RESERVED=1
    break
  fi
  sleep 2
done
if [ "$RESERVED" != "1" ]; then
  echo "expected exactly one reservation row for order $ORDER_ID before republishing" >&2
  exit 1
fi
echo "  confirmed: exactly one reservation exists for $ORDER_ID"

echo "== Capturing the exact OrderPlaced.v1 message this order produced =="
MESSAGE=$(docker exec fulfillops-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic fulfillops.order.events \
  --from-beginning \
  --timeout-ms 5000 2>/dev/null \
  | grep "\"aggregateId\":\"$ORDER_ID\"" | grep '"eventType":"OrderPlaced"' | head -1)
if [ -z "$MESSAGE" ]; then
  echo "could not find the OrderPlaced.v1 message for order $ORDER_ID on the topic" >&2
  exit 1
fi
echo "  captured original message"

duplicate_counter() {
  curl -sf "http://localhost:${INVENTORY_SERVICE_PORT}/actuator/prometheus" \
    | grep '^kafka_consumer_duplicate_total{' | grep 'eventType="OrderPlaced"' \
    | awk '{print $2}' | head -1
}
BEFORE=$(duplicate_counter)
BEFORE=${BEFORE:-0}

echo "== Republishing the exact same message (same eventId) onto the topic =="
echo "$MESSAGE" | docker exec -i fulfillops-kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic fulfillops.order.events >/dev/null

echo "== Waiting for inventory-service's inbox dedup to observe and ignore the duplicate =="
CAUGHT=0
for _ in $(seq 1 20); do
  after=$(duplicate_counter)
  after=${after:-0}
  if awk "BEGIN{exit !($after > $BEFORE)}"; then
    CAUGHT=1
    break
  fi
  sleep 2
done
if [ "$CAUGHT" != "1" ]; then
  echo "kafka_consumer_duplicate_total for OrderPlaced never increased after the replay" >&2
  exit 1
fi
echo "  confirmed: duplicate delivery counter increased"

FINAL_COUNT=$(reservation_count)
if [ "$FINAL_COUNT" != "1" ]; then
  echo "expected still exactly one reservation row after the duplicate delivery, found $FINAL_COUNT" >&2
  exit 1
fi

echo "== Failure scenario passed: duplicate delivery was detected and ignored, no double reservation =="
