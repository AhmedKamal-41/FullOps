# Runbook: Local Infrastructure

Covers `infra/compose/docker-compose.yml` (PostgreSQL, Kafka, Redis, Keycloak) and the four Spring Boot services running against it. See [`ARCHITECTURE.md`](../ARCHITECTURE.md) for why these four pieces of infrastructure exist.

## Prerequisites

- Docker and Docker Compose
- Java 21 and the bundled `./mvnw` (no separate Maven install needed)
- `cp .env.example .env` at the repository root — every command below assumes this file exists. All values in it are fictional and only valid against this local stack; see the comments in `.env.example` for what each one is for.

## Startup order

1. **`make infra-up`** — starts PostgreSQL, Kafka, Redis, and Keycloak, and blocks until Docker reports all four containers `healthy` (via `scripts/wait-for-infra-healthy.sh`). First run takes longer than later ones: Postgres runs its one-time database/user init script, and Keycloak does a one-time server augmentation build before it can import the realm — budget 1–2 minutes on the first run, well under a minute on later ones.
2. **`make run-order`** / `run-inventory` / `run-payment` / `run-fulfillment` — each starts one service, in its own terminal, against the infra from step 1. Each sources `.env` and runs with `SPRING_PROFILES_ACTIVE=local`. A service will fail fast at startup with a clear "could not resolve placeholder" error if `.env` is missing a value it needs — this is intentional (see "Configure Spring profiles" in `docs/PHASE_STATUS.md`), not a bug.
3. **`make smoke`** — an alternative to step 2 for a one-shot check: starts each of the four services itself (one at a time, `local` profile), obtains a fictional token from Keycloak, calls `/api/v1/whoami` with and without it, and stops every service it started. Requires step 1 to have already succeeded.
4. **`make infra-down`** — stops the infrastructure containers. Data is preserved in named Docker volumes (`postgres-data`, `redis-data`) by default. To also delete that data, run `make infra-down DOWN_ARGS=-v`.

Other useful targets: `make infra-status` (container health at a glance), `make logs` (follow all infra container logs).

## Why the fixed Keycloak hostname

`docker-compose.yml` sets `KC_HOSTNAME: http://localhost:8080` on the Keycloak service. Without this, a token requested through one network path (e.g., `localhost:8080` from the host) would carry a different `iss` claim than a token requested through another (e.g., a container-to-container hostname), and Spring Security would reject either one depending on which `issuer-uri` a service was configured with. Since every service in this repo runs on the host (not inside Compose) against infra published on `localhost`, pinning the issuer to `http://localhost:8080` keeps it consistent everywhere. If you later run a service inside Docker too, it will need this same `http://localhost:8080` issuer reachable from inside its container (e.g., via `host.docker.internal`), not a container-name-based URL.

## Troubleshooting

**A container is stuck `starting` or shows `unhealthy`.**
Run `make logs` (or `docker logs <container-name>`) to see why. Two failure modes are worth knowing about specifically, because they look like infrastructure problems but are actually config problems:
- Postgres/Redis health checks run *inside* the container and can only see environment variables that were explicitly passed to that container via `environment:` in `docker-compose.yml` — not variables only used in `command:` or only known to `docker compose` itself. If a health check starts failing after you edit `docker-compose.yml`, check that any variable it references is actually present in that service's `environment:` block.
- Keycloak's health endpoint lives on port `9000` (the management interface), not `8080`. `docker-compose.yml`'s health check reflects this; a manual `curl localhost:8080/health/ready` will not work.

**A service fails at startup with "Could not resolve placeholder '...'."**
`.env` is missing a value, or you ran a service without sourcing it (use `make run-order`, etc., not a bare `./mvnw spring-boot:run`). This is deliberate fail-fast behavior — see `docs/PHASE_STATUS.md` Phase 2 — not something to work around with a default value in the YAML.

**A service fails at startup with a JWT/OAuth2 or "issuer" related error.**
Confirm Keycloak is healthy and reachable at `http://localhost:8080/realms/fulfillops` (`curl http://localhost:8080/realms/fulfillops` should return realm metadata). If Keycloak was recently restarted, its in-memory dev database resets and it re-imports `infra/keycloak/realm-export.json` from scratch — this is expected; the fictional users, roles, and clients come back exactly as committed.

**Port already in use (5432, 9092, 6379, or 8080).**
Something else on your machine is using that port — stop it, or change the published port on the left-hand side of the relevant `ports:` entry in `docker-compose.yml` (the container-internal port on the right must stay the same).

**`./mvnw` fails with a Java/Maven version error before anything else runs.**
The root `pom.xml` enforces Java 21 and Maven 3.9+ via the Maven Enforcer plugin. Point `JAVA_HOME` at a JDK 21 install.

**Testcontainers-based tests (`./mvnw verify`) fail with a Docker-related error.**
Testcontainers needs a working Docker daemon, independent of whether `make infra-up` has been run — integration tests start their own throwaway Postgres/Kafka/Redis containers and do not touch the Compose stack (see `src/test/.../config/TestcontainersConfiguration.java` in any service). Confirm `docker ps` works at all first.

## Fictional credentials

Every credential referenced by `.env.example`, `docker-compose.yml`, and `infra/keycloak/realm-export.json` — database passwords, the Keycloak bootstrap admin, the three demo users (`customer.demo`, `operator.demo`, `admin.demo`), and the `fulfillops-cli` client secret — is fictional, committed intentionally, and valid only against this local stack. None of it is a real secret, and none of it should be reused anywhere else.
