# Contributing to FulfillOps

Thanks for looking at FulfillOps. It is a portfolio project built in ordered phases (see
[`docs/PHASE_STATUS.md`](docs/PHASE_STATUS.md)), so contributions are mostly about keeping the
code and its documented claims honest and readable.

## The one rule that shapes everything: plain, readable code

Every coding, editing, refactoring, testing, migration, scripting, and configuration change
follows the `plain-readable-code` style: clear over clever, functions that earn their existence,
basic language features first. Read
[`.claude/skills/plain-readable-code/SKILL.md`](.claude/skills/plain-readable-code/SKILL.md) and
[`AGENTS.md`](AGENTS.md) before writing. Among correct implementations, choose the one a
thoughtful junior developer can read in one pass and safely modify.

## Non-negotiable engineering rules

These come from [`CLAUDE.md`](CLAUDE.md) and apply everywhere:

- No service reads or writes another service's tables. No shared JPA/domain module.
- Never claim exactly-once processing; correctness comes from idempotent consumers and DB
  constraints.
- Money is `BigDecimal`, quantities are integers, timestamps are UTC `Instant`, ids are
  application-generated UUIDs (never a sequential DB id on the wire).
- Every retryable command endpoint takes an idempotency key; a reused key with a different
  payload is a conflict, not a false success.
- HTTP errors use RFC 9457 Problem Details; never leak stack traces or secrets.
- Never store real payment-card data or real PII.
- Do not publish coverage/throughput/latency/test-count claims until a command in this repo
  actually produced them.

## Building and checking

```
cp .env.example .env
make verify-all            # every feasible check: backend, frontend, kustomize, terraform, compose
```

Or individually:

```
./mvnw -B test             # unit + web-slice tests (no Docker)
./mvnw -B verify           # adds Testcontainers integration tests + coverage gate (needs Docker)
./mvnw -B spotless:apply   # auto-format (Google Java Format); spotless:check runs in verify
cd apps/ops-console && npm ci && npm run lint && npm test && npm run build
```

The build requires **JDK 21** (enforced by the Maven Enforcer plugin) and Maven is bundled via
`./mvnw`. See [`docs/TESTING.md`](docs/TESTING.md) for the full testing strategy.

## Commits and PRs

- Keep commits focused and messages descriptive (`type(scope): summary`, e.g.
  `feat(inventory): ...`, `docs: ...`).
- A change to product code should keep the docs it affects accurate — especially any claim in
  `README.md`, `docs/TESTING.md`, or `docs/PHASE_STATUS.md`.
- Run `scripts/audit-repo.sh` before opening a PR; it checks required docs/screenshots exist,
  relative links resolve, no obvious secrets are committed, README commands are real, and the
  metrics evidence is present.

## Reporting security concerns

See [`SECURITY.md`](SECURITY.md).
