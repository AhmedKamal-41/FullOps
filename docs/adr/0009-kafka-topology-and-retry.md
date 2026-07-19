# ADR 0009: Kafka topic/key/header conventions, and Spring Kafka's native retry/DLT over Resilience4j

## Status

Accepted

## Context

Phase 3 needed to decide three concrete things ADR 0001–0008 left open: how many Kafka topics exist and how they're named, what the message key and headers carry versus what only lives in the JSON body, and which library handles retry-with-backoff and dead-lettering for consumers.

## Decision

**Topics.** One topic per producing service, named `fulfillops.<service>.events` (e.g. `fulfillops.order.events`), not one topic per event type. A service's events are already ordered relative to each other by being on the same topic/partition; splitting by event type would only add topics to manage without adding a guarantee anything in this system needs.

**Partitioning key.** The Kafka message key is the order ID (`aggregateId`), not the event ID and not each service's own internal ID. Every event in a given order's saga — across all four services — lands on the same partition, which gives per-order ordering for free and makes Order Service's operations projection (ADR 0008) buildable by simply grouping on one field. See `contracts/events/README.md` for why `aggregateId` is always the order ID.

**Headers vs. body.** `eventId`, `eventType`, `eventVersion`, `correlationId`, and `causationId` (when present) are set as Kafka record headers in addition to appearing in the JSON envelope body. The body is the source of truth; the headers exist so tooling (and humans watching a topic) can identify a message without deserializing and parsing JSON first.

**Retry and dead-lettering: Spring Kafka's own `@RetryableTopic`, not Resilience4j.** Spring Kafka (bundled with Spring Boot's `spring-boot-starter-kafka`) ships non-blocking retry with automatic retry-topic and dead-letter-topic creation, exception-based routing (`exclude = NonRetryableEventProcessingException.class` skips straight to the DLT — see `docs/DOMAIN_MODEL.md`'s failure categories and "never retry business rejections" in `CLAUDE.md`), and configurable backoff, all as first-party, already-present functionality. Resilience4j is a general-purpose resilience library with no special Kafka integration; adopting it here would mean verifying its compatibility with Spring Boot 4.1 and Spring Kafka 4.1 ourselves, for a retry/DLT feature Spring Kafka already provides natively. Per `CLAUDE.md`'s instruction to use Resilience4j only if an officially verified compatible version exists, and to prefer supported Spring mechanisms otherwise: this project does not use Resilience4j.

## Consequences

- Retry topics are auto-created with a delay-value suffix (`fulfillops.order.events-retry-500`, `-retry-1000`, ...) and the DLT as `fulfillops.order.events-dlt` — a detail worth knowing when writing a test or a runbook that needs to name a specific topic, since the suffix is the backoff delay, not a sequential index.
- Every consumer's retry/DLT behavior is configured the same way (`@RetryableTopic` + a `NonRetryableEventProcessingException` exclusion), so a new listener in a later phase follows an established, already-tested pattern instead of inventing one.
- If a future requirement needs resilience patterns Spring Kafka doesn't cover (e.g., a circuit breaker around an outbound HTTP call, not a Kafka concern at all), that would be evaluated on its own merits then — this decision is scoped to Kafka consumer retry/DLT only.
