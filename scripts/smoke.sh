#!/usr/bin/env bash
# Starts all four services locally against the running Docker Compose infra,
# obtains a fictional token from Keycloak, calls each service's /api/v1/whoami
# with and without that token, and stops every service it started. Fails loudly
# on the first problem instead of leaving a partial, confusing state behind.
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

echo "== Obtaining a fictional token for customer.demo =="
TOKEN=$(curl -sf -X POST "$OIDC_ISSUER_URI/protocol/openid-connect/token" \
  -d "grant_type=password" \
  -d "client_id=$OIDC_CLI_CLIENT_ID" \
  -d "client_secret=$OIDC_CLI_CLIENT_SECRET" \
  -d "username=customer.demo" \
  -d "password=CustomerDemo!123" \
  -d "scope=openid fulfillops-api" \
  | python3 -c "import sys, json; print(json.load(sys.stdin)['access_token'])")
echo "  token acquired (${#TOKEN} characters)"

declare -A SERVICE_PORT=(
  [order-service]="$ORDER_SERVICE_PORT"
  [inventory-service]="$INVENTORY_SERVICE_PORT"
  [payment-service]="$PAYMENT_SERVICE_PORT"
  [fulfillment-service]="$FULFILLMENT_SERVICE_PORT"
)

PIDS=()
cleanup() {
  echo "== Stopping services started by this script =="
  for pid in "${PIDS[@]:-}"; do
    kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
}
trap cleanup EXIT

for service in order-service inventory-service payment-service fulfillment-service; do
  port="${SERVICE_PORT[$service]}"
  echo "== Starting $service (profile: local) on port $port =="
  SPRING_PROFILES_ACTIVE=local ./mvnw -q -pl "services/$service" spring-boot:run \
    >"/tmp/${service}-smoke.log" 2>&1 &
  PIDS+=("$!")

  echo "  waiting for /actuator/health/readiness ..."
  ready=false
  for _ in $(seq 1 60); do
    if curl -sf "http://localhost:${port}/actuator/health/readiness" >/dev/null 2>&1; then
      ready=true
      break
    fi
    sleep 2
  done
  if [ "$ready" != "true" ]; then
    echo "$service never became ready. See /tmp/${service}-smoke.log" >&2
    exit 1
  fi
  echo "  readiness OK (public, no token required)"

  echo "  calling /api/v1/whoami with the fictional token"
  curl -sf "http://localhost:${port}/api/v1/whoami" -H "Authorization: Bearer $TOKEN" | python3 -c "
import sys, json
body = json.load(sys.stdin)
assert body['username'] == 'customer.demo', body
assert 'ROLE_CUSTOMER' in body['roles'], body
print('  whoami response:', body)
"

  echo "  calling /api/v1/whoami with no token (expect 401)"
  status=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${port}/api/v1/whoami")
  if [ "$status" != "401" ]; then
    echo "expected 401 for unauthenticated whoami on $service, got $status" >&2
    exit 1
  fi
  echo "  unauthenticated rejection OK"

  kill "${PIDS[-1]}" 2>/dev/null || true
  wait "${PIDS[-1]}" 2>/dev/null || true
  PIDS=("${PIDS[@]:0:${#PIDS[@]}-1}")
done

echo "== Smoke test passed for all four services =="
