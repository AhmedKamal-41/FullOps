# Phase Status

This file is the source of truth for what is actually done. A phase is marked complete only after its acceptance criteria have been verified in this repository — not when a document describing it has been written.

| Phase | Outcome | Resume signal | Status |
|---|---|---|---|
| 0 | Product charter, architecture, agent rules | Clear scope and engineering judgment | **Complete** |
| 1 | Buildable four-service monorepo | Java 21, Spring Boot, Maven | **Complete** |
| 2 | Reproducible local infrastructure and migrations | Docker, PostgreSQL, Kafka, Redis, OAuth2 | **Complete** |
| 3 | Versioned events, outbox/inbox, correlation | Event-driven reliability | Not started |
| 4 | Secure, idempotent Order Service | REST API and transactional design | Not started |
| 5 | Race-safe Inventory Service | Concurrency and no-oversell guarantees | Not started |
| 6 | Resilient Payment Service simulator | Failure classification and idempotency | Not started |
| 7 | Fulfillment workflow and operator controls | Real operational workflow | Not started |
| 8 | Saga compensation and recovery | Distributed-systems correctness | Not started |
| 9 | Operations read model and SLA APIs | Operations analytics and exception management | Not started |
| 10 | Professional operations console | Demonstrable product and analyst usability | Not started |
| 11 | Metrics, traces, alerts, load/failure tests | Production reliability and observability | Not started |
| 12 | CI/CD, supply-chain checks, deployment packaging | Delivery engineering | Not started |
| 13 | Documentation, screenshots, demo, resume proof | Portfolio credibility | Not started |
| 14 | Claude final adversarial audit | Honest, verified claims | Not started |
| Final | Cursor independent wrap-up | Readability, polish, and final integration proof | Not started |

## Phase 0 — verification

- [x] `.claude/skills/plain-readable-code/SKILL.md` exists verbatim as the canonical skill.
- [x] `.cursor/rules/plain-readable-code.mdc` exists with `alwaysApply: true` and the same rules.
- [x] `.cursor/rules/fulfillops.mdc` exists with the project's non-negotiable engineering rules.
- [x] `CLAUDE.md` requires loading `plain-readable-code` before every coding/review task.
- [x] `AGENTS.md` states `plain-readable-code` as the primary style for every agent and describes the Claude-builds/Cursor-finishes execution model.
- [x] `README.md` describes the product without claiming any unimplemented capability as done; every forward-looking feature is labeled `(planned)`.
- [x] `docs/PROJECT_CHARTER.md`, `docs/ARCHITECTURE.md`, `docs/DOMAIN_MODEL.md` exist and are internally consistent with each other and with the ADRs.
- [x] `docs/adr/0001`–`0008` exist and cover service boundaries, choreography, outbox/inbox, at-least-once delivery, JSON Schema contracts, PostgreSQL per service, Keycloak/OIDC, and operations projection ownership.
- [x] No Spring Boot application code, Kafka topics, or database schemas exist yet — Phase 0 is documentation and agent configuration only.

## Phase 1 — verification

- [x] Root `pom.xml` is a Maven multi-module aggregator, parented on `org.springframework.boot:spring-boot-starter-parent:4.1.0` (current stable Spring Boot 4.1.x, confirmed against Maven Central metadata — Java 17–26 supported), pinned to Java 21.
- [x] Maven wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/`) generated via `maven-wrapper-plugin:3.3.4`, targeting Maven 3.9.16 (current stable, confirmed against Maven Central metadata; Maven 4 is still pre-release).
- [x] Exactly four modules under `services/`: `order-service`, `inventory-service`, `payment-service`, `fulfillment-service`, each with its own artifact name, base package (`com.ahmedali.fulfillops.<domain>`), `application.yml`, Actuator health/info endpoints, and one context-load test. No business entities, Kafka, or database code.
- [x] Root POM centralizes plugin versions and wires up Maven Enforcer (JDK 21 + Maven 3.9+ required, verified to fail fast under JDK 25), Surefire, Failsafe (scaffolded, no integration tests yet), JaCoCo (report only — no coverage threshold gate yet; see the plugin config comment for why), and Spotless (Google Java Format, bound to `verify`).
- [x] `.editorconfig`, `.gitignore`, `.gitattributes`, `.env.example`, `LICENSE` (MIT), and a `Makefile` with cross-platform Maven command targets exist at the root.
- [x] Each service has a minimal multi-stage Dockerfile (JDK 21 build stage, JRE 21 runtime stage, non-root user, Actuator-based `HEALTHCHECK`) plus a root `.dockerignore`. `order-service`'s image was built and run directly with Docker as a spot check: it started as the non-root user and its container health check reported `healthy`.
- [x] `.github/workflows/ci.yml` checks formatting (`spotless:check`), runs `./mvnw -B clean verify`, and builds (does not push) all four service images, using safe Maven dependency caching via `actions/setup-java`'s built-in `cache: maven`. This has not been run on GitHub — the claim below is limited to local verification.
- [x] `./mvnw -B clean verify` passes locally under JDK 21 with no Docker or external services required — all four modules build, all four context-load tests pass, formatting check passes.
- [x] All four applications were started independently (`java -jar ...`, outside of tests) and each reported `{"status":"UP"}` from `/actuator/health`.
- [x] No shared domain classes, Spring Cloud gateway, Kafka, databases, auth, frontend, or business endpoints were added.

Local-only verification performed: `./mvnw -B clean verify` (JDK 21, no Docker) and a direct `docker build`/`docker run` spot check of `order-service`. The GitHub Actions workflow has not actually run on GitHub yet, since nothing has been pushed — CI-green is not claimed until it is.

## Phase 2 — verification

- [x] `infra/compose/docker-compose.yml` starts PostgreSQL 17.10, Kafka 4.3.1 (KRaft, official `apache/kafka` image), Redis 8.8.0, and Keycloak 26.7.0 — every tag pinned and confirmed current-stable against each project's own registry metadata, never `latest`. All four containers have real health checks (verified: a broken health check for Postgres and Redis was caught and fixed during this phase — see the runbook's troubleshooting section for why).
- [x] `make infra-up` starts the stack and blocks until every container reports `healthy` (`scripts/wait-for-infra-healthy.sh`); `make infra-down` stops it without deleting volumes — verified by inspecting `docker volume ls` before and after, and by confirming all four databases were still present after a down/up cycle.
- [x] `infra/compose/postgres/init-databases.sh` creates four isolated databases (`order_db`, `inventory_db`, `payment_db`, `fulfillment_db`), each owned by its own user with `PUBLIC` connect privilege revoked — verified directly: `order_service` can connect to `order_db` and is denied connecting to `inventory_db` ("permission denied for database").
- [x] Each service has one Flyway baseline migration (`V1__baseline.sql`) creating only `outbox_event` and `inbox_event` (no domain tables), with indexes on event id (via primary key), aggregate id, state, created time, and next-attempt time, plus a `COMMENT ON DATABASE` ownership marker. Verified applying cleanly against a real (Testcontainers) Postgres in all four services.
- [x] `local`, `test`, and `production-like` Spring profiles exist per service. `local`/`production-like` read database, Kafka, Redis, and OIDC settings from environment variables with **no default values** — verified a service fails fast with a clear "could not resolve placeholder" error when a required variable is missing, rather than silently using a default. `test` uses Testcontainers `@ServiceConnection` instead of static config.
- [x] Each service is a native Spring Security OAuth2 Resource Server (`spring-boot-starter-security-oauth2-resource-server`, not a Keycloak adapter), with a `JwtAuthenticationConverter` mapping Keycloak's `realm_access.roles` claim to `ROLE_*` authorities and a custom audience validator requiring `fulfillops-api`. `/api/v1/whoami` is secured; `/actuator/health/**` and `/actuator/info` are public.
- [x] `infra/keycloak/realm-export.json` is an authoritative export (via Keycloak's own `kc.sh export`, not hand-written) of a real `fulfillops` realm: roles `CUSTOMER`/`OPERATOR`/`ADMIN`; three fictional demo users; a public SPA client (`fulfillops-console`) for the future ops console; a confidential client (`fulfillops-cli`) for local token retrieval; and a `fulfillops-api` audience client scope. Verified importable into a completely fresh Keycloak container with no manual steps.
- [x] Each service has a `TestcontainersConfiguration` (Postgres, Kafka, Redis via `@ServiceConnection`) and a `TestSecurityConfig` (network-free `JwtDecoder`, since a real `issuer-uri` would make context startup eagerly call an unreachable Keycloak). Every service has a migration-startup integration test (`*MigrationStartupIT`, routed to Failsafe, boots the full app against real Testcontainers-managed Postgres/Kafka/Redis and asserts `outbox_event`/`inbox_event` exist) and a JWT authorization test (`WhoAmIAuthorizationTest`, a `@WebMvcTest` using `spring-security-test`'s `jwt()` request post-processor, driving the actual `realm_access.roles → ROLE_*` conversion logic, not a hardcoded stand-in).
- [x] `make smoke` starts all four services against the live Compose stack, obtains a real fictional token from Keycloak for `customer.demo`, confirms `/actuator/health/readiness` is reachable with no token, confirms `/api/v1/whoami` returns 401 with no token, and confirms it returns the correct subject/username/`ROLE_CUSTOMER` with one — run successfully end to end for all four services, twice (once before, once after an infra down/up cycle).
- [x] `./mvnw -B clean verify` passes for the full reactor (unit tests + Testcontainers integration tests + formatting check), with no dependency on a running Compose stack.
- [x] `docs/runbooks/local-infrastructure.md` documents startup order and troubleshooting, including two real issues hit and fixed during this phase (container-scoped env vars for health checks; Keycloak's health endpoint being on port 9000, not 8080).
- [x] No domain tables, business endpoints, or cross-service coupling were added — only Order/Inventory/Payment/Fulfillment's own outbox/inbox scaffolding and the shared-in-spirit-only (not shared-in-code) infrastructure.

One correction made mid-phase, noted here for honesty: the first version of `infra/keycloak/realm-export.json` omitted the built-in `basic` client scope from both clients, so issued tokens had no `sub` claim and `/api/v1/whoami` returned a null subject. This was caught by actually reading the `whoami` response during the smoke test, not assumed — fixed by adding `basic` to both clients' default scopes and re-verified with a fresh Keycloak import.

## Next command

Phase 3 introduces versioned event contracts (`contracts/events/`), the transactional outbox relay and idempotent inbox consumer implementations (the tables already exist from Phase 2), and correlation/causation propagation. Review this Phase 2 output, commit it, and confirm the working tree is clean before starting Phase 3.
