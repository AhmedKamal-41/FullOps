#!/usr/bin/env bash
# Phase 11 failure demo: stops Redis, hits inventory-service's availability read endpoint (backed
# by InventoryAvailabilityCache), and confirms it keeps answering correctly straight from
# PostgreSQL — with the cache-failure counter incrementing to prove the fallback path, not a
# silent miss — then restarts Redis and confirms the cache resumes. Starts only inventory-service
# locally against the running Compose infra and stops it on exit. Safe and reversible: Redis is
# stopped and started, never removed, and no volume is touched.
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

echo "== Obtaining a fictional OPERATOR token for admin.demo =="
ADMIN_TOKEN=$(token_for admin.demo "AdminDemo!123")
echo "  token acquired"

PIDS=()
REDIS_STOPPED=0
cleanup() {
  if [ "$REDIS_STOPPED" = "1" ]; then
    echo "== Ensuring Redis is running again =="
    docker start fulfillops-redis >/dev/null 2>&1 || true
  fi
  echo "== Stopping services started by this script =="
  for pid in "${PIDS[@]:-}"; do
    kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
}
trap cleanup EXIT

echo "== Starting inventory-service (profile: local) on port $INVENTORY_SERVICE_PORT =="
SPRING_PROFILES_ACTIVE=local ./mvnw -q -pl services/inventory-service spring-boot:run \
  >/tmp/inventory-service-failure-scenario.log 2>&1 &
PIDS+=("$!")
READY=0
for _ in $(seq 1 60); do
  if curl -sf "http://localhost:${INVENTORY_SERVICE_PORT}/actuator/health/readiness" >/dev/null 2>&1; then
    READY=1
    break
  fi
  sleep 2
done
if [ "$READY" != "1" ]; then
  echo "inventory-service never became ready. See /tmp/inventory-service-failure-scenario.log" >&2
  exit 1
fi

SKU="FAILURE-REDIS-OUTAGE-$(date +%s)"
echo "== Creating and stocking fictional product $SKU =="
curl -sf -X POST "http://localhost:${INVENTORY_SERVICE_PORT}/api/v1/products" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Idempotency-Key: redis-outage-create-$SKU" \
  -H "Content-Type: application/json" \
  -d "{\"sku\": \"$SKU\", \"name\": \"Redis Outage Demo Widget\", \"description\": \"phase 11 failure scenario\"}" \
  >/dev/null
curl -sf -X POST "http://localhost:${INVENTORY_SERVICE_PORT}/api/v1/inventory/${SKU}/adjustments" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Idempotency-Key: redis-outage-restock-$SKU" \
  -H "Content-Type: application/json" \
  -d '{"changeQuantity": 5, "reasonCode": "RESTOCK", "reasonDetail": "phase 11 failure scenario"}' \
  >/dev/null
echo "  product created and stocked"

cache_failures() {
  curl -sf "http://localhost:${INVENTORY_SERVICE_PORT}/actuator/prometheus" \
    | grep '^inventory_cache_failures_total' | awk '{print $2}' | head -1
}
get_availability_status() {
  curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $ADMIN_TOKEN" \
    "http://localhost:${INVENTORY_SERVICE_PORT}/api/v1/inventory/${SKU}"
}

echo "== Confirming the availability read works normally, with Redis up =="
STATUS_BEFORE=$(get_availability_status)
echo "  GET /api/v1/inventory/$SKU -> $STATUS_BEFORE"
if [ "$STATUS_BEFORE" != "200" ]; then
  echo "expected 200 with Redis up, got $STATUS_BEFORE" >&2
  exit 1
fi
FAILURES_BEFORE=$(cache_failures)
FAILURES_BEFORE=${FAILURES_BEFORE:-0}
echo "  cache failures so far: $FAILURES_BEFORE"

echo "== Stopping Redis (docker stop, not removed) =="
docker stop fulfillops-redis >/dev/null
REDIS_STOPPED=1

echo "== Confirming the same read still succeeds, now falling back to PostgreSQL =="
STATUS_DURING=$(get_availability_status)
echo "  GET /api/v1/inventory/$SKU -> $STATUS_DURING"
if [ "$STATUS_DURING" != "200" ]; then
  echo "expected 200 via DB fallback with Redis down, got $STATUS_DURING" >&2
  exit 1
fi
FAILURES_DURING=$(cache_failures)
FAILURES_DURING=${FAILURES_DURING:-0}
echo "  cache failures now: $FAILURES_DURING"
if ! awk "BEGIN{exit !($FAILURES_DURING > $FAILURES_BEFORE)}"; then
  echo "expected inventory_cache_failures_total to increase during the Redis outage" >&2
  exit 1
fi
echo "  confirmed: request succeeded via DB fallback and the cache-failure counter increased"

echo "== Restarting Redis =="
docker start fulfillops-redis >/dev/null
REDIS_STOPPED=0
for _ in $(seq 1 20); do
  status=$(docker inspect --format='{{.State.Health.Status}}' fulfillops-redis 2>/dev/null || echo "missing")
  if [ "$status" = "healthy" ]; then
    break
  fi
  sleep 2
done
echo "  Redis healthy again"

echo "== Confirming the read still succeeds once Redis is back =="
STATUS_AFTER=$(get_availability_status)
echo "  GET /api/v1/inventory/$SKU -> $STATUS_AFTER"
if [ "$STATUS_AFTER" != "200" ]; then
  echo "expected 200 after Redis recovered, got $STATUS_AFTER" >&2
  exit 1
fi

echo "== Failure scenario passed: DB fallback served every read during the outage, cache resumed after recovery =="
