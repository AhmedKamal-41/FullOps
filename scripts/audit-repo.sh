#!/usr/bin/env bash
#
# Repository audit for the portfolio documentation. Checks that the evidence a reviewer relies
# on actually exists and holds together: required docs and screenshots are present, relative
# markdown links resolve, no obvious secret is committed, the commands the README tells people
# to run really exist, the Compose files parse, and the metrics evidence is present.
#
# Fast by default. Pass --full to also run the unit tests (slower).
#
# Exit code is non-zero if any check fails, so it can gate a commit or run in CI.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

FAILURES=0
fail() {
  echo "  FAIL: $1"
  FAILURES=$((FAILURES + 1))
}
ok() { echo "  ok:   $1"; }

section() {
  echo ""
  echo "== $1 =="
}

# --- 1. Required docs exist --------------------------------------------------------------
section "Required documents"
REQUIRED_DOCS=(
  "README.md" "CONTRIBUTING.md" "SECURITY.md" "LICENSE" ".env.example"
  "docs/ARCHITECTURE.md" "docs/DOMAIN_MODEL.md" "docs/TESTING.md" "docs/EVENT_CATALOG.md"
  "docs/KPI_DICTIONARY.md" "docs/SECURITY.md" "docs/KNOWN_LIMITATIONS.md"
  "docs/RESUME_EVIDENCE.md" "docs/PHASE_STATUS.md" "docs/RELEASE.md"
  "docs/adr/README.md" "docs/demo/DEMO_SCRIPT.md" "docs/demo/FAILURE_DEMO.md"
  "docs/FINAL_AUDIT.md"
)
for doc in "${REQUIRED_DOCS[@]}"; do
  if [ -f "$doc" ]; then ok "$doc"; else fail "missing document: $doc"; fi
done

# --- 2. Required screenshots exist -------------------------------------------------------
section "Required screenshots"
REQUIRED_SCREENSHOTS=(
  "docs/screenshots/01-login.png" "docs/screenshots/02-overview.png"
  "docs/screenshots/03-work-queue.png" "docs/screenshots/04-order-detail.png"
  "docs/screenshots/05-incidents.png" "docs/screenshots/06-inventory-risk.png"
  "docs/screenshots/07-fulfillment-board.png"
)
for shot in "${REQUIRED_SCREENSHOTS[@]}"; do
  if [ -s "$shot" ]; then ok "$shot"; else fail "missing or empty screenshot: $shot"; fi
done

# --- 3. Relative markdown links resolve --------------------------------------------------
# Only checks relative links (skips http(s)/mailto and pure #anchors). Strips a trailing
# #anchor before checking the file exists.
section "Relative markdown links"
link_failures=0
rm -f /tmp/audit-link-failures
while IFS= read -r mdfile; do
  dir="$(dirname "$mdfile")"
  # Extract the target of every ](...) link on the line.
  grep -oE '\]\([^)]+\)' "$mdfile" | sed -E 's/^\]\(//; s/\)$//' | while IFS= read -r target; do
    case "$target" in
      http://*|https://*|mailto:*|"#"*) continue ;;
    esac
    path="${target%%#*}"
    [ -z "$path" ] && continue
    if [ ! -e "$dir/$path" ]; then
      echo "  FAIL: broken link in $mdfile -> $target"
      echo "x" >> /tmp/audit-link-failures
    fi
  done
done < <(find . -name '*.md' -not -path './node_modules/*' -not -path './apps/*/node_modules/*')
if [ -f /tmp/audit-link-failures ]; then
  link_failures=$(wc -l < /tmp/audit-link-failures)
  rm -f /tmp/audit-link-failures
fi
if [ "$link_failures" -gt 0 ]; then
  FAILURES=$((FAILURES + link_failures))
else
  ok "all relative markdown links resolve"
fi

# --- 4. Secret scanning ------------------------------------------------------------------
# High-signal patterns only. This repo intentionally contains FICTIONAL local-only passwords
# (in .env.example, the compose files, and the Keycloak realm export), so it does not flag the
# word "password" — it looks for real credential shapes and for a committed .env.
section "Secret scan"
if git rev-parse --is-inside-work-tree >/dev/null 2>&1 && git ls-files --error-unmatch .env >/dev/null 2>&1; then
  fail ".env is tracked by git — it must be git-ignored"
else
  ok ".env is not committed"
fi
secret_hits=$(grep -rInE -- '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{36}|xox[baprs]-[A-Za-z0-9-]+' \
  --include='*.java' --include='*.ts' --include='*.tsx' --include='*.yml' --include='*.yaml' \
  --include='*.json' --include='*.sh' --include='*.tf' . 2>/dev/null | grep -v 'node_modules' || true)
if [ -n "$secret_hits" ]; then
  fail "possible committed secret(s):"
  echo "$secret_hits" | sed 's/^/    /'
else
  ok "no high-signal secret patterns found"
fi

# --- 5. README commands are real ---------------------------------------------------------
# Every `make <target>` mentioned in the README must exist as a Makefile target.
section "README commands"
readme_targets=$(grep -oE 'make [a-z][a-z0-9-]+' README.md | awk '{print $2}' | sort -u)
for target in $readme_targets; do
  if grep -qE "^${target}:" Makefile; then
    ok "make $target"
  else
    fail "README references 'make $target' but it is not a Makefile target"
  fi
done

# --- 6. Compose files parse --------------------------------------------------------------
section "Compose configuration"
if command -v docker >/dev/null 2>&1; then
  env_file=.env
  [ -f .env ] || env_file=.env.example
  if docker compose --env-file "$env_file" -f infra/compose/docker-compose.yml config -q 2>/dev/null; then
    ok "base compose config valid"
  else
    fail "base compose config invalid"
  fi
  if docker compose --env-file "$env_file" -f infra/compose/docker-compose.yml \
      -f infra/compose/docker-compose.apps.yml --profile demo config -q 2>/dev/null; then
    ok "demo compose config valid"
  else
    fail "demo compose config invalid"
  fi
else
  echo "  skip: docker not installed"
fi

# --- 7. Metrics evidence present ---------------------------------------------------------
section "Metrics evidence"
for ev in order-submission ops-work-queue mixed-scenario; do
  f="docs/evidence/k6/${ev}-summary.json"
  if [ -s "$f" ]; then ok "$f"; else fail "missing k6 evidence: $f"; fi
done

# --- 8. Tests (optional, --full) ---------------------------------------------------------
section "Tests"
if [ "${1:-}" = "--full" ]; then
  if ./mvnw -B -q test >/tmp/audit-test.log 2>&1; then
    ok "unit tests pass (./mvnw -B test)"
  else
    fail "unit tests failed — see /tmp/audit-test.log"
  fi
else
  echo "  skip: pass --full to run ./mvnw -B test"
fi

# --- Summary -----------------------------------------------------------------------------
echo ""
echo "==================================================================="
if [ "$FAILURES" -eq 0 ]; then
  echo "Audit passed — all checks clean."
  exit 0
else
  echo "Audit found $FAILURES issue(s)."
  exit 1
fi
