#!/usr/bin/env bash
# Phase 11 failure demo: publishes a malformed (non-JSON) message directly onto
# fulfillops.order.events — a "poison message" no retry can ever fix — and watches
# inventory-service exhaust its 4 retry attempts and route it to its dead-letter topic.
# Confirms a dead_letter_event row is persisted (queryable/replayable — see
# DeadLetterReplayService) and the kafka.consumer.dlt counter increments. Starts only
# inventory-service locally against the running Compose infra and stops it on exit. Safe and
# reversible: only ever appends a message to Kafka and a row to Postgres, nothing is deleted.
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

PIDS=()
cleanup() {
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
for _ in $(seq 1 60); do
  if curl -sf "http://localhost:${INVENTORY_SERVICE_PORT}/actuator/health/readiness" >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

dlt_counter() {
  curl -sf "http://localhost:${INVENTORY_SERVICE_PORT}/actuator/prometheus" \
    | grep '^kafka_consumer_dlt_total' | awk '{print $2}' | paste -sd+ | bc 2>/dev/null || echo 0
}
dead_letter_row_count() {
  docker exec fulfillops-postgres psql -U "$POSTGRES_SUPERUSER" -d inventory_db -tAc \
    "SELECT count(*) FROM dead_letter_event"
}

BEFORE_DLT=$(dlt_counter)
BEFORE_ROWS=$(dead_letter_row_count)
echo "== Baseline: kafka_consumer_dlt_total=$BEFORE_DLT, dead_letter_event rows=$BEFORE_ROWS =="

echo "== Publishing a malformed (non-JSON) poison message onto fulfillops.order.events =="
echo 'this is not valid JSON and can never be parsed as an EventEnvelope' \
  | docker exec -i fulfillops-kafka /opt/kafka/bin/kafka-console-producer.sh \
      --bootstrap-server localhost:9092 --topic fulfillops.order.events >/dev/null
echo "  message published"

echo "== Waiting for the retry budget (4 attempts, exponential backoff) to exhaust and route to the DLT =="
LANDED=0
for _ in $(seq 1 20); do
  after_rows=$(dead_letter_row_count)
  if [ "$after_rows" -gt "$BEFORE_ROWS" ]; then
    LANDED=1
    break
  fi
  sleep 2
done
if [ "$LANDED" != "1" ]; then
  echo "no new dead_letter_event row appeared after the poison message" >&2
  exit 1
fi
echo "  confirmed: a new dead_letter_event row was persisted (queryable and replayable)"

AFTER_DLT=$(dlt_counter)
echo "  kafka_consumer_dlt_total now: $AFTER_DLT"
if ! awk "BEGIN{exit !($AFTER_DLT > $BEFORE_DLT)}"; then
  echo "expected kafka_consumer_dlt_total to increase after the poison message" >&2
  exit 1
fi

echo "== Failure scenario passed: poison message exhausted retries and was safely dead-lettered =="
