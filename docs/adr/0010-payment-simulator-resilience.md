# ADR 0010: Resilience4j's framework-agnostic core libraries, not its Spring Boot starter, for the payment provider call

## Status

Accepted

## Context

Phase 6 needs bounded retry and a circuit breaker around Payment Service's call to its (simulated)
payment provider — a genuinely different concern from the Kafka consumer retry/DLT ADR 0009 already
settled. ADR 0009 explicitly scoped itself to Kafka only and flagged this exact situation as one to
evaluate separately: "If a future requirement needs resilience patterns Spring Kafka doesn't cover
(e.g., a circuit breaker around an outbound HTTP call, not a Kafka concern at all), that would be
evaluated on its own merits then."

`CLAUDE.md` requires using Resilience4j's Spring Boot starter only if an officially verified
Spring Boot 4.1-compatible version exists, and a supported alternative or Resilience4j's own core API
otherwise — never forcing an incompatible dependency.

## What was checked

Maven Central was queried directly (`search.maven.org`, not a cached mirror) for every
`io.github.resilience4j` artifact on 2026-07-19. The newest published version across the entire
Resilience4j group, including `resilience4j-spring-boot3` and the `resilience4j-bom`, is **2.3.0**.
No `resilience4j-spring-boot4` artifact exists on Maven Central at all (`numFound: 0`). Resilience4j's
own GitHub issue tracker (#2351, #2371, #2384, #2421, #2427) confirms Spring Boot 4 support was added
to a `resilience4j-spring-boot4` module in source, and separately confirms that module was omitted
from the `resilience4j-bom` and, as of the versions actually published, from Maven Central release
artifacts a project could depend on without pointing at an unreleased snapshot or building from
source. That is exactly the "not officially compatible yet" case `CLAUDE.md` anticipates.

## Decision

Use Resilience4j's framework-agnostic core libraries directly — `resilience4j-circuitbreaker`,
`resilience4j-retry`, `resilience4j-micrometer` (version 2.3.0, the same version verified above) —
instead of any `resilience4j-spring-boot*` starter. These three artifacts have no Spring Boot
dependency at all; they are plain Java libraries with their own registries and decorator API
(`Retry.decorateSupplier`, `CircuitBreaker.decorateSupplier`), so Spring Boot's major version is
irrelevant to whether they work. `PaymentProviderResilienceConfig` wires them by hand as ordinary
`@Bean` methods, reading configuration from `application.yml` the same way every other tunable in
this codebase does (plain `@Value` injection, no `@ConfigurationProperties` starter class), and binds
their events to the same `MeterRegistry` Actuator already exposes via `TaggedCircuitBreakerMetrics`/
`TaggedRetryMetrics` (also framework-agnostic — no Spring Boot coupling).

`Retry` wraps `CircuitBreaker`, not the other way around: `Retry.decorateSupplier(retry,
CircuitBreaker.decorateSupplier(circuitBreaker, call))`. Only `ProviderTimeoutException` and
`ProviderTemporaryErrorException` are configured as retryable/circuit-recordable exceptions — a
business decline is a return value, never an exception, so it can never trigger a retry or count
against the circuit breaker's failure rate. `CallNotPermittedException` (the circuit is open) is
outside `retry`'s configured `retryExceptions`, so it fails fast on the first attempt instead of
wasting retry attempts hammering a circuit that has already decided not to let calls through.

## Consequences

- No `spring-boot-starter-actuator` auto-configured Resilience4j health indicators or
  `/actuator/circuitbreakers` endpoint exist this phase — those come bundled with the Spring Boot
  starter this ADR deliberately avoids. Circuit/retry state is still fully observable through the
  Micrometer meters `TaggedCircuitBreakerMetrics`/`TaggedRetryMetrics` register, which
  `PaymentAuthorizationClient` and its tests both rely on.
- If a later phase revisits this once `resilience4j-spring-boot4` actually ships on Maven Central
  with a real BOM entry, switching is a dependency and configuration-class change only —
  `PaymentAuthorizationClient`'s decorator usage is plain Resilience4j core API either way.
- This decision is scoped to the payment provider call only. Kafka consumer retry/DLT for
  `InventoryReservedListener` and `OrderPlacedListener` still uses Spring Kafka's native
  `@RetryableTopic`, unchanged from ADR 0009.
