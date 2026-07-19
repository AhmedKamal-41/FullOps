# Phase Status

This file is the source of truth for what is actually done. A phase is marked complete only after its acceptance criteria have been verified in this repository — not when a document describing it has been written.

| Phase | Outcome | Resume signal | Status |
|---|---|---|---|
| 0 | Product charter, architecture, agent rules | Clear scope and engineering judgment | **Complete** |
| 1 | Buildable four-service monorepo | Java 21, Spring Boot, Maven | **Complete** |
| 2 | Reproducible local infrastructure and migrations | Docker, PostgreSQL, Kafka, Redis, OAuth2 | Not started |
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

## Next command

Phase 2 builds reproducible local infrastructure: Docker Compose for PostgreSQL (one database per service), Kafka, Redis, and Keycloak, plus each service's first Flyway migration. Review this Phase 1 output, commit it, and confirm the working tree is clean before starting Phase 2.
