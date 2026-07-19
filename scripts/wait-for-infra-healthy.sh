#!/usr/bin/env bash
# Polls until every infra container reports "healthy," or fails after a timeout.
# Used by `make infra-up` so that command doesn't return until the stack is
# actually usable, not just started.
set -euo pipefail

CONTAINERS=(fulfillops-postgres fulfillops-kafka fulfillops-redis fulfillops-keycloak)
MAX_ATTEMPTS=60

for attempt in $(seq 1 "$MAX_ATTEMPTS"); do
  all_healthy=true
  for name in "${CONTAINERS[@]}"; do
    status=$(docker inspect --format='{{.State.Health.Status}}' "$name" 2>/dev/null || echo "missing")
    if [ "$status" != "healthy" ]; then
      all_healthy=false
    fi
  done

  if [ "$all_healthy" = "true" ]; then
    echo "All infrastructure containers are healthy."
    exit 0
  fi

  sleep 5
done

echo "Timed out after $((MAX_ATTEMPTS * 5))s waiting for infrastructure to become healthy." >&2
echo "Run 'make infra-status' to see current state, or 'make logs' to see why." >&2
exit 1
