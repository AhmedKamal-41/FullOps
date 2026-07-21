# Convenience wrapper around Maven, Docker Compose, and script commands. Every
# target below is just the plain command spelled out — run that command directly
# (or mvnw.cmd on Windows) if you don't have `make` available.

COMPOSE = docker compose --env-file .env -f infra/compose/docker-compose.yml

.PHONY: verify format format-check test build docker-build clean \
        run-order run-inventory run-payment run-fulfillment \
        infra-up infra-down infra-status logs smoke smoke-inventory smoke-payment smoke-fulfillment \
        smoke-cancellation smoke-operations

verify: ## Full build: format check, compile, unit tests, coverage report.
	./mvnw -B clean verify

format: ## Reformat all Java sources in place.
	./mvnw -B spotless:apply

format-check: ## Fail if any Java source is not formatted.
	./mvnw -B spotless:check

test: ## Run unit tests only, skip the full verify lifecycle.
	./mvnw -B test

build: verify

docker-build: ## Build all four service images locally (no push).
	docker build -f services/order-service/Dockerfile -t fulfillops/order-service .
	docker build -f services/inventory-service/Dockerfile -t fulfillops/inventory-service .
	docker build -f services/payment-service/Dockerfile -t fulfillops/payment-service .
	docker build -f services/fulfillment-service/Dockerfile -t fulfillops/fulfillment-service .

# The run-* targets source .env into the shell environment so Spring Boot's
# ${ORDER_DB_PASSWORD}-style placeholders resolve, matching what infra-up expects
# to already be running. See docs/runbooks/local-infrastructure.md.
run-order: ## Run Order Service against the Compose infra (profile: local).
	set -a && . ./.env && set +a && SPRING_PROFILES_ACTIVE=local ./mvnw -pl services/order-service spring-boot:run

run-inventory: ## Run Inventory Service against the Compose infra (profile: local).
	set -a && . ./.env && set +a && SPRING_PROFILES_ACTIVE=local ./mvnw -pl services/inventory-service spring-boot:run

run-payment: ## Run Payment Service against the Compose infra (profile: local).
	set -a && . ./.env && set +a && SPRING_PROFILES_ACTIVE=local ./mvnw -pl services/payment-service spring-boot:run

run-fulfillment: ## Run Fulfillment Service against the Compose infra (profile: local).
	set -a && . ./.env && set +a && SPRING_PROFILES_ACTIVE=local ./mvnw -pl services/fulfillment-service spring-boot:run

clean:
	./mvnw -B clean

infra-up: ## Start Postgres, Kafka, Redis, and Keycloak, and wait until all are healthy.
	@test -f .env || (echo "Missing .env — run: cp .env.example .env" >&2 && exit 1)
	$(COMPOSE) up -d
	./scripts/wait-for-infra-healthy.sh

infra-down: ## Stop the infrastructure containers. Does NOT delete volumes/data — pass DOWN_ARGS=-v to also wipe them.
	$(COMPOSE) down $(DOWN_ARGS)

infra-status: ## Show infrastructure container status and health.
	$(COMPOSE) ps

logs: ## Follow infrastructure container logs (Ctrl-C to stop watching).
	$(COMPOSE) logs -f

smoke: ## Start all four services against the running infra, verify JWT + whoami + public health, then stop them.
	./scripts/smoke.sh

smoke-inventory: ## Create stock, place a real order, and observe the resulting Inventory event on Kafka.
	./scripts/smoke-inventory-reservation.sh

smoke-payment: ## Place one normal-priced and one seeded-decline-priced order, and observe both PaymentAuthorized/PaymentDeclined events on Kafka.
	./scripts/smoke-payment-authorization.sh

smoke-fulfillment: ## Place a normal-priced order and follow it to a real FulfillmentAssigned.v1 event, then confirm it via the operator API.
	./scripts/smoke-fulfillment-assignment.sh

smoke-cancellation: ## Place an order, request cancellation as the customer, and follow the compensation saga to CANCELLED.
	./scripts/smoke-cancellation.sh

smoke-operations: ## Exercise the ops API end to end: KPI overview, work queue, backlog, timeline, and a projection rebuild.
	./scripts/smoke-operations.sh
