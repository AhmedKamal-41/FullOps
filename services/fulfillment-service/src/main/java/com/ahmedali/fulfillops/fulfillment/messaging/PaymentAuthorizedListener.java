package com.ahmedali.fulfillops.fulfillment.messaging;

import com.ahmedali.fulfillops.fulfillment.service.FulfillmentAssignmentService;
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
 * Fulfillment Service's assignment trigger: reacts to payment-service's PaymentAuthorized.v1. Same
 * check-then-process-then-record inbox shape as every other listener in this codebase. Ignores
 * every other event type on the same topic (PaymentDeclined.v1, PaymentRefunded.v1), since only an
 * authorized payment starts a fulfillment.
 */
@Component
public class PaymentAuthorizedListener {

  private static final Logger log = LoggerFactory.getLogger(PaymentAuthorizedListener.class);
  private static final String CONSUMER_NAME = "fulfillment-service.payment-authorized";
  private static final String PAYMENT_AUTHORIZED_EVENT_TYPE = "PaymentAuthorized";

  private final InboxEventRepository inboxEventRepository;
  private final FulfillmentAssignmentService fulfillmentAssignmentService;
  private final ObjectMapper objectMapper;

  public PaymentAuthorizedListener(
      InboxEventRepository inboxEventRepository,
      FulfillmentAssignmentService fulfillmentAssignmentService,
      ObjectMapper objectMapper) {
    this.inboxEventRepository = inboxEventRepository;
    this.fulfillmentAssignmentService = fulfillmentAssignmentService;
    this.objectMapper = objectMapper;
  }

  @RetryableTopic(
      attempts = "4",
      backOff = @BackOff(delay = 500, multiplier = 2.0, maxDelay = 5000),
      exclude = NonRetryableEventProcessingException.class)
  @KafkaListener(
      topics = "${app.messaging.payment-events-topic}",
      groupId = "${spring.application.name}")
  @Transactional
  public void onMessage(String envelopeJson) {
    EventEnvelope envelope = objectMapper.readValue(envelopeJson, EventEnvelope.class);
    if (!PAYMENT_AUTHORIZED_EVENT_TYPE.equals(envelope.eventType())) {
      log.debug("ignoring event type={} on the payment events topic", envelope.eventType());
      return;
    }

    MDC.put("correlationId", envelope.correlationId().toString());
    MDC.put("eventId", envelope.eventId().toString());
    try {
      InboxEventId id = new InboxEventId(envelope.eventId(), CONSUMER_NAME);
      if (inboxEventRepository.existsById(id)) {
        log.info(
            "duplicate delivery of PaymentAuthorized for order {}, already processed, skipping",
            envelope.aggregateId());
        return;
      }

      fulfillmentAssignmentService.assign(
          envelope.aggregateId(), envelope.correlationId(), envelope.eventId());

      inboxEventRepository.save(new InboxEvent(id, envelope.eventType(), envelope.aggregateId()));
      log.info("processed PaymentAuthorized for order {}", envelope.aggregateId());
    } finally {
      MDC.remove("correlationId");
      MDC.remove("eventId");
    }
  }

  @DltHandler
  public void onDlt(String envelopeJson) {
    // Deliberately does not log envelopeJson itself — failure metadata (event type, id) is safe
    // to log; a raw payload might not be, so it isn't logged wholesale.
    EventEnvelope envelope = objectMapper.readValue(envelopeJson, EventEnvelope.class);
    log.error(
        "event routed to dead-letter topic after exhausting retries: type={} eventId={} orderId={}",
        envelope.eventType(),
        envelope.eventId(),
        envelope.aggregateId());
  }
}
