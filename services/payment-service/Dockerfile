# syntax=docker/dockerfile:1
#
# Build context is the repository root (this service's pom.xml depends on the root
# reactor pom and the sibling service pom.xml files just to resolve the module tree,
# not on any sibling source code — see docs/adr/0001-service-boundaries.md).
#   docker build -f services/payment-service/Dockerfile -t fulfillops/payment-service .
#
# The base images are pinned to an immutable multi-arch index digest (not just the
# 21-jdk/21-jre-noble tag) so a rebuild always resolves to the exact same base — the
# tag is kept alongside the digest only as a human-readable hint. Refresh both digests
# together when bumping the JDK; docs/RELEASE.md documents how.

FROM eclipse-temurin:25-jdk-noble@sha256:3eb81ed94d8c1a34422f19f8188548bdf02cae69c91d0328afdbb7abed90f617 AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY services/order-service/pom.xml services/order-service/pom.xml
COPY services/inventory-service/pom.xml services/inventory-service/pom.xml
COPY services/payment-service/pom.xml services/payment-service/pom.xml
COPY services/fulfillment-service/pom.xml services/fulfillment-service/pom.xml
RUN ./mvnw -q -pl services/payment-service -am dependency:go-offline

COPY services/payment-service/src services/payment-service/src
RUN ./mvnw -q -pl services/payment-service -am package -DskipTests

FROM eclipse-temurin:25-jre-noble@sha256:2f1da100788559b397bcf48c736169ea5b070bde84e55f203bbee8e83d87a175

# Supplied by the build (CI passes the real values); the defaults keep a bare
# `docker build` working locally. These only feed the OCI labels below — no secret
# is ever passed as a build argument.
ARG SERVICE_VERSION=0.1.0-SNAPSHOT
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown
LABEL org.opencontainers.image.title="FulfillOps Payment Service" \
      org.opencontainers.image.description="Deterministic payment simulator: authorization, decline classification, refunds. No real card data." \
      org.opencontainers.image.version="${SERVICE_VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.vendor="FulfillOps" \
      org.opencontainers.image.base.name="docker.io/library/eclipse-temurin:21-jre-noble"

RUN useradd --system --create-home --shell /usr/sbin/nologin fulfillops
USER fulfillops
WORKDIR /app
COPY --from=build /workspace/services/payment-service/target/payment-service.jar app.jar

EXPOSE 8083
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8083/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
