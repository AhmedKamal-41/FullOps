# syntax=docker/dockerfile:1
#
# Build context is the repository root (this service's pom.xml depends on the root
# reactor pom and the sibling service pom.xml files just to resolve the module tree,
# not on any sibling source code — see docs/ARCHITECTURE.md).
#   docker build -f services/fulfillment-service/Dockerfile -t fulfillops/fulfillment-service .
#
# The base images are pinned to an immutable multi-arch index digest (not just the
# 21-jdk/21-jre-noble tag) so a rebuild always resolves to the exact same base — the
# tag is kept alongside the digest only as a human-readable hint. Refresh both digests
# together when bumping the JDK.

FROM eclipse-temurin:21-jdk-noble@sha256:35685c7e23352983a48882d97cd9875f5284c228db71d1e2476e5e6c1bab1080 AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY contracts/pom.xml contracts/pom.xml
COPY services/order-service/pom.xml services/order-service/pom.xml
COPY services/inventory-service/pom.xml services/inventory-service/pom.xml
COPY services/payment-service/pom.xml services/payment-service/pom.xml
COPY services/fulfillment-service/pom.xml services/fulfillment-service/pom.xml
RUN ./mvnw -q -pl services/fulfillment-service -am dependency:go-offline

COPY services/fulfillment-service/src services/fulfillment-service/src
RUN ./mvnw -q -pl services/fulfillment-service -am package -DskipTests

FROM eclipse-temurin:21-jre-noble@sha256:373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64

# Supplied by the build (CI passes the real values); the defaults keep a bare
# `docker build` working locally. These only feed the OCI labels below — no secret
# is ever passed as a build argument.
ARG SERVICE_VERSION=0.1.0-SNAPSHOT
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown
LABEL org.opencontainers.image.title="FulfillOps Fulfillment Service" \
      org.opencontainers.image.description="Warehouse assignment, the fulfillment workflow, operator controls, and cancellation." \
      org.opencontainers.image.version="${SERVICE_VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.vendor="FulfillOps" \
      org.opencontainers.image.base.name="docker.io/library/eclipse-temurin:21-jre-noble"

RUN useradd --system --create-home --shell /usr/sbin/nologin fulfillops
USER fulfillops
WORKDIR /app
COPY --from=build /workspace/services/fulfillment-service/target/fulfillment-service.jar app.jar

EXPOSE 8084
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8084/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
