package com.ahmedali.fulfillops.inventory.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the inbox pattern end to end by consuming this service's own outbox topic — no service
 * consumes its own events in the real design, so once Phase 4+ adds real cross-service listeners,
 * this class stops being the only example. What it demonstrates still applies to every future
 * listener: check-then-process-then-record in one transaction, and let @RetryableTopic separate
 * transient failures (retried with backoff) from business rejections (straight to the DLT, never
 * retried).
 */
@Component
public class InboxEventListener {

  private static final Logger log = LoggerFactory.getLogger(InboxEventListener.class);
  private static final String CONSUMER_NAME = "inventory-service.inbox-example";

  private final InboxEventRepository inboxEventRepository;
  private final ObjectMapper objectMapper;

  public InboxEventListener(InboxEventRepository inboxEventRepository, ObjectMapper objectMapper) {
    this.inboxEventRepository = inboxEventRepository;
    this.objectMapper = objectMapper;
  }

  @RetryableTopic(
      attempts = "4",
      backOff = @BackOff(delay = 500, multiplier = 2.0, maxDelay = 5000),
      exclude = NonRetryableEventProcessingException.class)
  @KafkaListener(topics = "${app.messaging.topic}", groupId = "${spring.application.name}")
  @Transactional
  public void onMessage(String envelopeJson) {
    EventEnvelope envelope = objectMapper.readValue(envelopeJson, EventEnvelope.class);
    MDC.put("correlationId", envelope.correlationId().toString());
    MDC.put("eventId", envelope.eventId().toString());
    try {
      InboxEventId id = new InboxEventId(envelope.eventId(), CONSUMER_NAME);
      if (inboxEventRepository.existsById(id)) {
        log.info(
            "duplicate delivery of event type={}, already processed, skipping",
            envelope.eventType());
        return;
      }

      process(envelope);

      inboxEventRepository.save(new InboxEvent(id, envelope.eventType(), envelope.aggregateId()));
      log.info("processed event type={}", envelope.eventType());
    } finally {
      MDC.remove("correlationId");
      MDC.remove("eventId");
    }
  }

  @DltHandler
  public void onDlt(String envelopeJson) {
    // Deliberately does not log envelopeJson itself — failure metadata (event type,
    // id) is safe to log; a raw payload might not be, so it isn't logged wholesale.
    EventEnvelope envelope = objectMapper.readValue(envelopeJson, EventEnvelope.class);
    log.error(
        "event routed to dead-letter topic after exhausting retries: type={} eventId={}",
        envelope.eventType(),
        envelope.eventId());
  }

  // Phase 3 has no business lifecycle yet (see docs/PHASE_STATUS.md), so there is no
  // real domain effect to apply here. What's under test is the mechanism itself: a
  // payload's optional "simulateFailure" field lets tests drive the retryable and
  // non-retryable paths without any real domain logic existing to drive them with.
  private void process(EventEnvelope envelope) {
    JsonNode simulateFailure = envelope.payload().get("simulateFailure");
    if (simulateFailure == null) {
      return;
    }
    String mode = simulateFailure.asString();
    if ("retryable".equals(mode)) {
      throw new RuntimeException("simulated transient failure");
    }
    if ("non-retryable".equals(mode)) {
      throw new NonRetryableEventProcessingException("simulated business rejection");
    }
  }
}
