# Convenience wrapper around Maven commands. Every target below is just the plain
# ./mvnw command spelled out — run that command directly (or mvnw.cmd on Windows)
# if you don't have `make` available.

.PHONY: verify format format-check test build docker-build clean \
        run-order run-inventory run-payment run-fulfillment

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

run-order: ## Run Order Service with the test/local profile.
	./mvnw -pl services/order-service spring-boot:run

run-inventory: ## Run Inventory Service with the test/local profile.
	./mvnw -pl services/inventory-service spring-boot:run

run-payment: ## Run Payment Service with the test/local profile.
	./mvnw -pl services/payment-service spring-boot:run

run-fulfillment: ## Run Fulfillment Service with the test/local profile.
	./mvnw -pl services/fulfillment-service spring-boot:run

clean:
	./mvnw -B clean
