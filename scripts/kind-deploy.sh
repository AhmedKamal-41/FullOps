#!/usr/bin/env bash
#
# Local kind deployment for FulfillOps. Builds the four service images, loads them into
# a kind cluster, deploys the Kustomize manifests, waits for every rollout, runs a smoke
# check, and tears the cluster down again.
#
# The stateful infrastructure (PostgreSQL, Kafka, Redis, Keycloak) is NOT deployed into
# the cluster — it stays in Docker Compose on the host (run `make infra-up` first). The
# pods reach it at host.docker.internal (see infra/kubernetes/base/configmap.yaml). This
# trade-off is documented in infra/kubernetes/README.md.
#
# Safety: this script only ever creates and deletes a cluster named exactly
# "fulfillops". It never touches any other kind cluster, and it never deletes a Docker
# volume.
#
# Usage:
#   scripts/kind-deploy.sh            # up -> smoke -> down (self-contained demo)
#   scripts/kind-deploy.sh up         # create cluster and deploy, leave it running
#   scripts/kind-deploy.sh smoke      # smoke-test a running deployment
#   scripts/kind-deploy.sh down       # delete the fulfillops cluster only

set -euo pipefail

CLUSTER_NAME="fulfillops"
NAMESPACE="fulfillops"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KUBECTL="kubectl --context kind-${CLUSTER_NAME}"
SERVICES="order-service inventory-service payment-service fulfillment-service"

require_tools() {
  for tool in kind kubectl docker; do
    if ! command -v "$tool" >/dev/null 2>&1; then
      echo "ERROR: '$tool' is required but not installed." >&2
      exit 1
    fi
  done
}

build_and_load_images() {
  for service in $SERVICES; do
    echo "== building fulfillops/${service}:local =="
    docker build -f "${REPO_ROOT}/services/${service}/Dockerfile" \
      -t "fulfillops/${service}:local" "${REPO_ROOT}"
    echo "== loading fulfillops/${service}:local into kind =="
    kind load docker-image "fulfillops/${service}:local" --name "${CLUSTER_NAME}"
  done
}

create_secret_from_env() {
  # The per-service passwords come from the repo .env (the same fictional local-only
  # values Docker Compose uses). They are read into a Secret, never committed to a
  # manifest.
  if [ ! -f "${REPO_ROOT}/.env" ]; then
    echo "ERROR: ${REPO_ROOT}/.env not found — run: cp .env.example .env" >&2
    exit 1
  fi
  # shellcheck disable=SC1091
  set -a && . "${REPO_ROOT}/.env" && set +a

  $KUBECTL create secret generic fulfillops-secrets \
    --namespace "${NAMESPACE}" \
    --from-literal=ORDER_DB_PASSWORD="${ORDER_DB_PASSWORD}" \
    --from-literal=INVENTORY_DB_PASSWORD="${INVENTORY_DB_PASSWORD}" \
    --from-literal=PAYMENT_DB_PASSWORD="${PAYMENT_DB_PASSWORD}" \
    --from-literal=FULFILLMENT_DB_PASSWORD="${FULFILLMENT_DB_PASSWORD}" \
    --from-literal=REDIS_PASSWORD="${REDIS_PASSWORD}" \
    --dry-run=client -o yaml | $KUBECTL apply -f -
}

up() {
  require_tools

  if kind get clusters | grep -qx "${CLUSTER_NAME}"; then
    echo "kind cluster '${CLUSTER_NAME}' already exists — reusing it."
  else
    echo "== creating kind cluster '${CLUSTER_NAME}' =="
    kind create cluster --config "${REPO_ROOT}/infra/kubernetes/kind-cluster.yaml"
  fi

  build_and_load_images

  # The namespace must exist before the secret is created inside it.
  $KUBECTL apply -f "${REPO_ROOT}/infra/kubernetes/base/namespace.yaml"
  create_secret_from_env

  echo "== applying manifests =="
  $KUBECTL apply -k "${REPO_ROOT}/infra/kubernetes/overlays/kind"

  echo "== waiting for rollouts =="
  for service in $SERVICES; do
    $KUBECTL -n "${NAMESPACE}" rollout status "deployment/${service}" --timeout=300s
  done
}

smoke() {
  # A rollout only reports ready once each pod's readiness probe passes, which for these
  # services means the app booted, migrated its database, and connected to Kafka/Redis.
  # So a successful rollout is itself the core smoke signal; here we additionally confirm
  # each service answers a real readiness request over a port-forward.
  echo "== smoke test: readiness of each service =="
  local port=18080
  for service in $SERVICES; do
    $KUBECTL -n "${NAMESPACE}" port-forward "svc/${service}" "${port}:$(service_port "${service}")" >/dev/null 2>&1 &
    local pf_pid=$!
    sleep 3
    if curl -fsS "http://localhost:${port}/actuator/health/readiness" | grep -q '"status":"UP"'; then
      echo "  ${service}: READY"
    else
      echo "  ${service}: NOT READY" >&2
      kill "${pf_pid}" 2>/dev/null || true
      return 1
    fi
    kill "${pf_pid}" 2>/dev/null || true
    port=$((port + 1))
  done
  echo "smoke test passed."
}

service_port() {
  case "$1" in
    order-service) echo 8081 ;;
    inventory-service) echo 8082 ;;
    payment-service) echo 8083 ;;
    fulfillment-service) echo 8084 ;;
  esac
}

down() {
  # Only ever deletes the one named cluster.
  if kind get clusters | grep -qx "${CLUSTER_NAME}"; then
    echo "== deleting kind cluster '${CLUSTER_NAME}' =="
    kind delete cluster --name "${CLUSTER_NAME}"
  else
    echo "kind cluster '${CLUSTER_NAME}' does not exist — nothing to delete."
  fi
}

case "${1:-all}" in
  up) up ;;
  smoke) smoke ;;
  down) down ;;
  all)
    up
    smoke
    down
    ;;
  *)
    echo "Usage: $0 [up|smoke|down|all]" >&2
    exit 1
    ;;
esac
