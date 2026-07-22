#!/usr/bin/env bash
# Phase 11 failure demo: stops the Kafka broker, places orders while it's down (the outbox
# pattern means the REST write still succeeds — see docs/adr/0004-at-least-once-delivery.md),
# watches Order Service's outbox_backlog_count and outbox_oldest_unpublished_age_seconds gauges
# grow via /actuator/prometheus, then restarts Kafka and watches the backlog drain back to zero.
# Starts only order-service locally against the running Compose infra and stops it on exit.
# Safe and reversible: Kafka is stopped and started, never removed, and no volume is touched.
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
KAFKA_STOPPED=0
cleanup() {
  if [ "$KAFKA_STOPPED" = "1" ]; then
    echo "== Ensuring Kafka is running again =="
    docker start fulfillops-kafka >/dev/null 2>&1 || true
  fi
  echo "== Stopping services started by this script =="
  for pid in "${PIDS[@]:-}"; do
    kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
}
trap cleanup EXIT

echo "== Starting order-service (profile: local) on port $ORDER_SERVICE_PORT =="
SPRING_PROFILES_ACTIVE=local ./mvnw -q -pl services/order-service spring-boot:run \
  >/tmp/order-service-failure-scenario.log 2>&1 &
PIDS+=("$!")
READY=0
for _ in $(seq 1 60); do
  if curl -sf "http://localhost:${ORDER_SERVICE_PORT}/actuator/health/readiness" >/dev/null 2>&1; then
    READY=1
    break
  fi
  sleep 2
done
if [ "$READY" != "1" ]; then
  echo "order-service never became ready. See /tmp/order-service-failure-scenario.log" >&2
  exit 1
fi

backlog_count() {
  curl -sf "http://localhost:${ORDER_SERVICE_PORT}/actuator/prometheus" \
    | grep '^outbox_backlog_count' | awk '{print $2}' | head -1
}

echo "== Baseline outbox backlog: $(backlog_count) =="

echo "== Stopping the Kafka broker (docker stop, not removed) =="
docker stop fulfillops-kafka >/dev/null
KAFKA_STOPPED=1

SKU="FAILURE-KAFKA-OUTAGE-$(date +%s)"
echo "== Placing 5 orders for fictional SKU $SKU while Kafka is down =="
for i in $(seq 1 5); do
  curl -sf -X POST "http://localhost:${ORDER_SERVICE_PORT}/api/v1/orders" \
    -H "Authorization: Bearer $CUSTOMER_TOKEN" \
    -H "Idempotency-Key: kafka-outage-$SKU-$i" \
    -H "Content-Type: application/json" \
    -d "{\"items\": [{\"sku\": \"$SKU\", \"quantity\": 1, \"unitPrice\": {\"currencyCode\": \"USD\", \"amount\": \"9.99\"}}]}" \
    >/dev/null
  echo "  order $i placed (REST write succeeded despite Kafka being down)"
done

echo "== Waiting for the outbox backlog gauge to reflect the growth =="
GREW=0
for _ in $(seq 1 15); do
  count=$(backlog_count)
  echo "  outbox_backlog_count=$count"
  if [ -n "$count" ] && awk "BEGIN{exit !($count >= 5)}"; then
    GREW=1
    break
  fi
  sleep 2
done
if [ "$GREW" != "1" ]; then
  echo "outbox backlog never grew to at least 5 — the outage wasn't reflected in the metric" >&2
  exit 1
fi
echo "  confirmed: outbox backlog grew while Kafka was down"

echo "== Restarting the Kafka broker =="
docker start fulfillops-kafka >/dev/null
KAFKA_STOPPED=0
for _ in $(seq 1 30); do
  status=$(docker inspect --format='{{.State.Health.Status}}' fulfillops-kafka 2>/dev/null || echo "missing")
  if [ "$status" = "healthy" ]; then
    break
  fi
  sleep 2
done
echo "  Kafka healthy again"

echo "== Waiting for the outbox backlog to drain back to zero =="
DRAINED=0
for _ in $(seq 1 30); do
  count=$(backlog_count)
  echo "  outbox_backlog_count=$count"
  if [ "$count" = "0" ] || [ "$count" = "0.0" ]; then
    DRAINED=1
    break
  fi
  sleep 2
done
if [ "$DRAINED" != "1" ]; then
  echo "outbox backlog never drained back to zero after Kafka recovered" >&2
  exit 1
fi

echo "== Failure scenario passed: outbox backlog grew during the outage and drained after recovery =="
