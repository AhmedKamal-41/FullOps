package com.ahmedali.fulfillops.payment.messaging;

import com.ahmedali.fulfillops.payment.service.AuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Payment Service's authorization trigger: reacts to inventory-service's InventoryReserved.v1. Same
 * check-then-process-then-record inbox shape as every other listener in this codebase. If
 * AuthorizationService can't find this order's context yet (OrderPlacedListener hasn't caught up)
 * or the provider's retry budget is exhausted, the resulting exception is deliberately left
 * unhandled here so it propagates and this @RetryableTopic gives it a later, less contended,
 * chance; a business decline never throws, so it never gets retried.
 */
@Component
public class InventoryReservedListener {

  private static final Logger log = LoggerFactory.getLogger(InventoryReservedListener.class);
  private static final String CONSUMER_NAME = "payment-service.inventory-reserved";
  private static final String INVENTORY_RESERVED_EVENT_TYPE = "InventoryReserved";

  private final InboxEventRepository inboxEventRepository;
  private final AuthorizationService authorizationService;
  private final KafkaListenerMetrics metrics;
  private final ObjectMapper objectMapper;

  public InventoryReservedListener(
      InboxEventRepository inboxEventRepository,
      AuthorizationService authorizationService,
      KafkaListenerMetrics metrics,
      ObjectMapper objectMapper) {
    this.inboxEventRepository = inboxEventRepository;
    this.authorizationService = authorizationService;
    this.metrics = metrics;
    this.objectMapper = objectMapper;
  }

  @RetryableTopic(
      attempts = "4",
      backOff = @BackOff(delay = 500, multiplier = 2.0, maxDelay = 5000),
      exclude = NonRetryableEventProcessingException.class)
  @KafkaListener(
      topics = "${app.messaging.inventory-events-topic}",
      groupId = "${spring.application.name}")
  @Transactional
  public void onMessage(String envelopeJson) {
    EventEnvelope envelope = objectMapper.readValue(envelopeJson, EventEnvelope.class);
    if (!INVENTORY_RESERVED_EVENT_TYPE.equals(envelope.eventType())) {
      // inventory-service's outbox topic also carries InventoryRejected.v1/InventoryReleased.v1,
      // neither of which starts an authorization attempt.
      log.debug("ignoring event type={} on the inventory events topic", envelope.eventType());
      return;
    }

    MDC.put("correlationId", envelope.correlationId().toString());
    MDC.put("eventId", envelope.eventId().toString());
    MDC.put("aggregateId", envelope.aggregateId().toString());
    try {
      InboxEventId id = new InboxEventId(envelope.eventId(), CONSUMER_NAME);
      if (inboxEventRepository.existsById(id)) {
        log.info(
            "duplicate delivery of InventoryReserved for order {}, already processed, skipping",
            envelope.aggregateId());
        metrics.recordDuplicate(envelope.eventType());
        return;
      }

      try {
        authorizationService.authorize(
            envelope.aggregateId(), envelope.correlationId(), envelope.eventId());
      } catch (RuntimeException processingFailure) {
        log.warn(
            "processing failed for {} on order {}, errorClass={}",
            envelope.eventType(),
            envelope.aggregateId(),
            processingFailure.getClass().getSimpleName());
        metrics.recordProcessingFailure(
            envelope.eventType(), processingFailure.getClass().getSimpleName());
        throw processingFailure;
      }

      inboxEventRepository.save(new InboxEvent(id, envelope.eventType(), envelope.aggregateId()));
      log.info("processed InventoryReserved for order {}", envelope.aggregateId());
    } finally {
      MDC.remove("correlationId");
      MDC.remove("eventId");
      MDC.remove("aggregateId");
    }
  }

  @DltHandler
  public void onDlt(String envelopeJson) {
    // Deliberately does not log envelopeJson itself — failure metadata (event type, id) is safe
    // to log; a raw payload might not be, so it isn't logged wholesale.
    EventEnvelope envelope = objectMapper.readValue(envelopeJson, EventEnvelope.class);
    metrics.recordDeadLettered(envelope.eventType());
    log.error(
        "event routed to dead-letter topic after exhausting retries: type={} eventId={} orderId={}",
        envelope.eventType(),
        envelope.eventId(),
        envelope.aggregateId());
  }
}
