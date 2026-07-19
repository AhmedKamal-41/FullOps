# syntax=docker/dockerfile:1
#
# Build context is the repository root (this service's pom.xml depends on the root
# reactor pom and the sibling service pom.xml files just to resolve the module tree,
# not on any sibling source code — see docs/adr/0001-service-boundaries.md).
#   docker build -f services/order-service/Dockerfile -t fulfillops/order-service .

FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY services/order-service/pom.xml services/order-service/pom.xml
COPY services/inventory-service/pom.xml services/inventory-service/pom.xml
COPY services/payment-service/pom.xml services/payment-service/pom.xml
COPY services/fulfillment-service/pom.xml services/fulfillment-service/pom.xml
RUN ./mvnw -q -pl services/order-service -am dependency:go-offline

COPY services/order-service/src services/order-service/src
RUN ./mvnw -q -pl services/order-service -am package -DskipTests

FROM eclipse-temurin:21-jre-noble
RUN useradd --system --create-home --shell /usr/sbin/nologin fulfillops
USER fulfillops
WORKDIR /app
COPY --from=build /workspace/services/order-service/target/order-service.jar app.jar

EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8081/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
