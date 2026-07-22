#!/usr/bin/env bash
#
# One-command local verification. Runs every check that this machine can actually run and
# clearly skips the ones whose tooling is not installed, instead of failing on them. It is
# the local mirror of what CI enforces (see .github/workflows/).
#
# A check that runs and fails makes this script exit non-zero. A check that is skipped for
# a missing tool does not.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

PASSED=()
FAILED=()
SKIPPED=()

have() { command -v "$1" >/dev/null 2>&1; }

run_check() {
  local name="$1"
  shift
  echo ""
  echo "==================================================================="
  echo ">> ${name}"
  echo "==================================================================="
  if "$@"; then
    PASSED+=("$name")
  else
    FAILED+=("$name")
  fi
}

skip_check() {
  echo ""
  echo ">> SKIP: $1 ($2)"
  SKIPPED+=("$1")
}

# --- Backend -----------------------------------------------------------------------------
# The Maven build enforces JDK 21; if it is not on the PATH, skip rather than fail with a
# confusing enforcer error.
if have java && java -version 2>&1 | grep -q '"21'; then
  run_check "backend: format check" ./mvnw -B spotless:check
  if docker info >/dev/null 2>&1; then
    run_check "backend: full verify (unit + integration + coverage gate)" ./mvnw -B verify
  else
    run_check "backend: unit tests and ArchUnit (Docker down, integration tests skipped)" ./mvnw -B test
  fi
else
  skip_check "backend" "JDK 21 not found on PATH"
fi

# --- Frontend ----------------------------------------------------------------------------
if have npm; then
  run_check "frontend: install, lint, test, build" bash -c '
    cd apps/ops-console &&
    npm ci &&
    npm run lint &&
    npm run test &&
    npm run build'
else
  skip_check "frontend" "npm not installed"
fi

# --- Kubernetes manifests ----------------------------------------------------------------
if have kubectl; then
  run_check "k8s: kustomize base builds" kubectl kustomize infra/kubernetes/base
  run_check "k8s: kustomize kind overlay builds" kubectl kustomize infra/kubernetes/overlays/kind
else
  skip_check "kubernetes" "kubectl not installed"
fi

# --- Terraform ---------------------------------------------------------------------------
if have terraform; then
  run_check "terraform: fmt check" terraform -chdir=infra/terraform fmt -check -recursive
  run_check "terraform: validate" bash -c '
    cd infra/terraform &&
    terraform init -backend=false -input=false >/dev/null &&
    terraform validate'
else
  skip_check "terraform" "terraform not installed"
fi

# --- Compose config ----------------------------------------------------------------------
if have docker; then
  ENV_FILE=.env
  [ -f .env ] || ENV_FILE=.env.example
  run_check "compose: base config valid" docker compose --env-file "$ENV_FILE" -f infra/compose/docker-compose.yml config -q
  run_check "compose: demo overlay config valid" docker compose --env-file "$ENV_FILE" \
    -f infra/compose/docker-compose.yml -f infra/compose/docker-compose.apps.yml --profile demo config -q
else
  skip_check "compose" "docker not installed"
fi

# --- Summary -----------------------------------------------------------------------------
echo ""
echo "==================================================================="
echo "Verification summary"
echo "==================================================================="
printf 'PASSED (%d): %s\n' "${#PASSED[@]}" "${PASSED[*]:-none}"
printf 'SKIPPED (%d): %s\n' "${#SKIPPED[@]}" "${SKIPPED[*]:-none}"
printf 'FAILED (%d): %s\n' "${#FAILED[@]}" "${FAILED[*]:-none}"

if [ "${#FAILED[@]}" -gt 0 ]; then
  exit 1
fi
echo "All checks that could run on this machine passed."
