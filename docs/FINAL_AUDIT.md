# FulfillOps — Final Adversarial Release Audit (Phase 14)

**Auditor stance:** a skeptical senior Java reviewer, SRE, security reviewer, and operations
manager reading the whole repository looking for reasons *not* to ship. No features were added.
The goal is an honest, evidence-backed release verdict.

**Verdict: READY WITH DOCUMENTED LIMITATIONS.** No Critical or High issue was found. The system's
correctness, security, and reliability claims are backed by code and by tests that pass; the
documented limitations are about *what could be re-verified in this build environment*, not about
defects in the system. Details and evidence below.

---

## 1. Method and clean-state evidence

All checks were run against the current working tree (this is not a git repository; the working
tree is the source of truth) after `mvn clean`, so no stale `target/` artifact influenced a
result. The build environment provides only JDK 8/17 (the build enforces JDK 21, so a Temurin 21
was used) and an unreliable Docker/Testcontainers layer — this bounds what could be executed here
and is called out wherever relevant.

| Check | Command | Result |
| --- | --- | --- |
| Clean unit + web-slice + ArchUnit | `./mvnw -B clean test` | **BUILD SUCCESS — 176 tests, 0 failures** (contracts 14, order 70, inventory 39, payment 30, fulfillment 23) |
| Formatting / readability gate | `./mvnw -B spotless:check` | Clean |
| Package-boundary rules | ArchUnit (in the run above) | 12/12 pass |
| Event-contract validation | contracts module (in the run above) | 14/14 pass |
| Repository/doc audit | `bash scripts/audit-repo.sh` | All checks clean (docs, screenshots, links, secrets, README commands, compose config, k6 evidence) |
| Kustomize builds | `kubectl kustomize base` / `overlays/kind` | Both build (18 resources) |
| Compose config | `docker compose … config` (base + demo) | Both valid |

**Could not be executed here (environment, not defect):** the Testcontainers integration suite
(40 `*IT.java`), including the concurrency and end-to-end reliability tests, because Docker was not
reliably available; `terraform`/`tflint`/`trivy`/`syft`/`kind`/`helm` are not installed. These
were exercised and recorded in earlier phases (see `docs/PHASE_STATUS.md`), but the Phase 14
request to *re-run concurrency/e2e tests repeatedly to expose flakiness* could not be honored in
this environment. This is the single biggest reason the verdict is "with documented limitations"
rather than unqualified READY. See M1/M2 below.

---

## 2. Findings by severity

### Critical — none.
### High — none.

### Medium

**M1 — Full coverage figure is unmeasured; the gate is a conservative unvalidated floor.**
- *Evidence:* `coverage.business.line.minimum = 0.60` in the root `pom.xml`; unit-only business
  coverage measured 16–26% per service; the merged unit+integration figure was never produced
  because the integration suite could not run here.
- *Impact:* the gate may be weaker than it should be, or (less likely) could fail the first real
  CI run if true coverage is below 0.60. No coverage claim is made in the README beyond the wired
  floor, so nothing is overstated — but the gate is not yet empirically calibrated.
- *Reproduction:* `./mvnw -B verify` on a Docker-capable machine; read `target/site/jacoco-merged`.
- *Recommended next action:* run CI, read the real number, set the floor just under it. Already
  flagged in `docs/TESTING.md` and the Phase 12/13 status. **Not fixed here** (cannot measure).

**M2 — Concurrency / saga / e2e reliability not re-verified from a clean state this phase.**
- *Evidence:* the guarantees (no-oversell, compensation, reconciliation single-run) are covered by
  `ReservationConcurrencyIT`, `CancellationSagaIT`, `OrderCancellationConcurrencyIT`,
  `ReconciliationServiceIT`, `FulfillmentConcurrencyIT`, etc. — which passed in Phases 5–8 — but
  Docker was unavailable here, so they were not re-run, let alone repeated to expose flakiness.
- *Impact:* the reliability claims rest on prior-phase runs plus code review, not on a fresh
  Phase-14 run. Code review found the mechanisms sound (row locks, `@Version`, advisory lock,
  inbox dedup, unique constraints), but that is not a substitute for repeated execution.
- *Attempt made this phase:* Docker Desktop was launched and polled for ~2.5 minutes; it did not
  become ready (a `docker run hello-world` never succeeded), consistent with the same instability
  seen in Phases 12–13. So the re-run genuinely could not be performed here, not merely skipped.
- *Reproduction:* on a Docker-capable machine, `./mvnw -B verify` (optionally loop the concurrency
  ITs, e.g. `for i in 1..10; do ./mvnw -pl services/inventory-service test -Dtest=ReservationConcurrencyIT; done`).
- *Recommended next action:* run these in CI and loop them once to confirm stability. **Not fixed
  here** (cannot run).

**M3 — Known flaky test under resource contention: `OrderConcurrencyIT`.**
- *Evidence:* Phases 8–11 recorded that this test can fail intermittently under this sandbox's
  memory pressure while passing cleanly in isolation; its production code path was never the cause.
- *Impact:* CI on a small runner could see an intermittent red that is environmental, not a real
  regression.
- *Recommended next action:* give the concurrency ITs a dedicated, less-contended CI job, or add a
  documented Awaitility bound; do **not** paper over it with blanket retries. **Not fixed here**
  (cannot reproduce without Docker; fixing blind would risk masking a real signal).

### Low

**L1 — Stale, misleading class comment in `order-service` `SecurityConfig`. → FIXED.**
- The Javadoc said "Only /api/v1/whoami is secured this phase; health and info stay public" — a
  Phase-2 leftover contradicting the actual, rich rule set. This is exactly the "comment that
  misleads" that `plain-readable-code` warns against. Rewritten to describe the real rules; the
  other three services' comments were checked and are accurate. Authorization tests still pass
  (24/24). Behavior-preserving (comment only).

**L2 — Coverage exclusion `config/**` also excludes `SecurityConfig`'s role-mapping logic.**
- `realmRolesAsAuthorities` is real authorization logic but lives in an excluded package, so it
  does not count toward the coverage gate. It **is** tested (`WhoAmIAuthorizationTest` drives it
  through MockMvc), so no untested logic is hidden — the exclusion is defensible and conventional.
  *No fix; noted for transparency.*

**L3 — Terraform DB security-group egress is `0.0.0.0/0`.**
- Ingress is correctly restricted to the VPC CIDR; egress-all is the conventional default and is
  safe, but a strict IaC scanner (tfsec/Trivy) may flag it as advisory. *Accepted; the Terraform
  is a never-applied reference.*

**L4 — Swagger UI / `v3/api-docs` and `/actuator/prometheus` are `permitAll`.**
- Appropriate for a local/portfolio system (and Prometheus cannot present a bearer token to a
  scrape target). A real external deployment would put these behind network policy or auth.
  *Accepted; documented in `docs/KNOWN_LIMITATIONS.md` / `docs/SECURITY.md`.*

---

## 3. Area-by-area audit results

| # | Area | Result | Key evidence |
| --- | --- | --- | --- |
| 1 | Build reproducibility / deps | **Pass** | Clean `mvn clean test` green; Java 21 pinned by Enforcer; base images digest-pinned; deps managed by Spring Boot BOM + explicit versions |
| 2 | Service boundaries / no coupling | **Pass** | ArchUnit forbids cross-service package deps (12 tests pass); no shared JPA module; grep found no cross-DB access |
| 3 | Transaction / outbox-inbox atomicity | **Pass** | `OrderCreationTransaction` writes order + history + outbox in one `@Transactional`; inbox keyed `(event_id, consumer_name)` |
| 4 | Idempotency payload mismatch | **Pass** | sha256 request fingerprint compared on replay; mismatch → `IdempotencyKeyConflictException` (409), verified by ITs in earlier phases |
| 5 | Duplicate / out-of-order / poison events | **Pass (code review)** | `@RetryableTopic` + `@DltHandler`; `NonRetryableEventProcessingException` skips retries; inbox dedup; out-of-order handled as retryable |
| 6 | Concurrency / no-oversell | **Pass (prior runs + review); not re-run — M2** | `SELECT ... FOR UPDATE` + `@Version`; `ReservationConcurrencyIT` asserts exactly 5 of 10 win, never negative |
| 7 | Saga / terminal-state consistency | **Pass (prior runs + review); not re-run — M2** | Choreographed compensation; `order_cancellation` `@Version`; finalizes only when all required compensations confirm |
| 8 | AuthN/Z, secrets, CORS, validation, exposure | **Pass** | Issuer + `fulfillops-api` audience validation; role rules + ownership checks; single-origin CORS; no card/PII fields; non-owner → 404 |
| 9 | Migrations, constraints, indexes, pagination | **Pass** | 62 index statements; unique constraints on `order_id` per service; pagination bounded by `max-page-size` |
| 10 | Metric cardinality, traces, alerts, log safety | **Pass** | Tags are bounded (`eventType`, `errorClass`) — grep found no id-valued tags; grep found no token/payload/password in any log statement |
| 11 | Test quality / coverage / CI | **Pass with M1/M3** | 176 unit tests green; coverage exclusions legitimate; gate floor unmeasured (M1); one env-flaky test (M3) |
| 12 | Accessibility / operator usability | **Pass** | axe assertions in unit (`Overview.test.tsx`, `WorkQueue.test.tsx`) and e2e (`overview.spec.ts`); `eslint-plugin-jsx-a11y` |
| 13 | Docker / K8s / IaC safety | **Pass** | Non-root, digest-pinned, no `:latest`/privileged/hostPath/root; K8s resource limits + probes + PDBs; Terraform ingress VPC-scoped, RDS-managed secret; never applied |
| 14 | Claim accuracy (README/evidence) | **Pass** | 176-test claim matches this run; k6 numbers (p95 889/359/506, 0% fail) match `docs/evidence/k6/*.json` exactly; no forbidden phrases except in negations |
| 15 | plain-readable-code adherence | **Pass** | Spotless clean; no TODO/FIXME/HACK; split-bean transactions, explicit control flow, descriptive names; one misleading comment found and fixed (L1) |

---

## 4. What was fixed in this audit

- **L1:** rewrote the stale/misleading `SecurityConfig` class comment in `order-service`
  (behavior-preserving; authorization tests re-run, 24/24 pass; Spotless clean).

No test, coverage gate, or security gate was lowered. No failing test was deleted. No measured
evidence was edited by hand.

---

## 5. Release-candidate checklist

- [x] Clean-state build passes (`mvn clean test`, 176 tests, 0 failures).
- [x] Formatting/readability gate clean (Spotless).
- [x] Package-boundary rules pass (ArchUnit).
- [x] Event contracts validate.
- [x] Security review: authN/Z, CORS, validation, data exposure — no Critical/High.
- [x] No secrets committed; `.env` git-ignored; only fictional local creds present.
- [x] Container/K8s/IaC safety: non-root, digest-pinned, limited, never auto-applied.
- [x] Docs/links/commands/evidence audit passes (`scripts/audit-repo.sh`).
- [x] README / resume figures match generated evidence (176 tests; k6 numbers).
- [x] Known limitations explicit (`docs/KNOWN_LIMITATIONS.md`).
- [ ] **Integration/concurrency/e2e suite re-run from clean state** — *pending a Docker-capable
  machine / CI* (M2).
- [ ] **Full coverage figure measured and gate calibrated** — *pending CI* (M1).
- [ ] **CI green on GitHub** — *nothing pushed yet.*

The three unchecked items are all "run on capable infrastructure," not code defects.

---

## 6. Final verdict

**READY WITH DOCUMENTED LIMITATIONS.**

The code, security posture, and reliability mechanisms hold up under adversarial review, and every
claim in the repository maps to code or to evidence that matches. There is no Critical or High
issue and no unresolved High. The limitations that keep this from an unqualified READY are
environmental verification gaps — the integration/concurrency/e2e suite and the coverage number
must be produced on a Docker-capable machine or in CI — and they are documented honestly rather
than hidden. A reviewer with a normal Docker-capable machine can close them by running
`./mvnw -B verify` and the CI workflows.

Per the Phase 14 rule, this outcome (READY WITH DOCUMENTED LIMITATIONS) permits marking the phase
complete.
