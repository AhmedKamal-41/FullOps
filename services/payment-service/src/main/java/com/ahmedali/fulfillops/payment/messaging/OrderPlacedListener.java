package com.ahmedali.fulfillops.payment.messaging;

import com.ahmedali.fulfillops.payment.domain.OrderPaymentContext;
import com.ahmedali.fulfillops.payment.domain.OrderPaymentContextRepository;
import java.math.BigDecimal;
import java.util.UUID;
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
 * Builds Payment Service's own local copy of the order facts it needs (order id, customer id,
 * currency, amount — nothing else, per CLAUDE.md) by consuming order-service's OrderPlaced.v1.
 * Deliberately does not trigger authorization itself — InventoryReservedListener does that, once
 * stock is confirmed reserved, using the context this listener saves.
 */
@Component
public class OrderPlacedListener {

  private static final Logger log = LoggerFactory.getLogger(OrderPlacedListener.class);
  private static final String CONSUMER_NAME = "payment-service.order-placed";
  private static final String ORDER_PLACED_EVENT_TYPE = "OrderPlaced";

  private final InboxEventRepository inboxEventRepository;
  private final OrderPaymentContextRepository orderPaymentContextRepository;
  private final ObjectMapper objectMapper;

  public OrderPlacedListener(
      InboxEventRepository inboxEventRepository,
      OrderPaymentContextRepository orderPaymentContextRepository,
      ObjectMapper objectMapper) {
    this.inboxEventRepository = inboxEventRepository;
    this.orderPaymentContextRepository = orderPaymentContextRepository;
    this.objectMapper = objectMapper;
  }

  @RetryableTopic(
      attempts = "4",
      backOff = @BackOff(delay = 500, multiplier = 2.0, maxDelay = 5000),
      exclude = NonRetryableEventProcessingException.class)
  @KafkaListener(
      topics = "${app.messaging.order-events-topic}",
      groupId = "${spring.application.name}")
  @Transactional
  public void onMessage(String envelopeJson) {
    EventEnvelope envelope = objectMapper.readValue(envelopeJson, EventEnvelope.class);
    if (!ORDER_PLACED_EVENT_TYPE.equals(envelope.eventType())) {
      // order-service's outbox topic may carry other event types (OrderCancelled.v1,
      // OrderRequiresReview.v1, ...) that Payment has no reason to react to here.
      log.debug("ignoring event type={} on the order events topic", envelope.eventType());
      return;
    }

    MDC.put("correlationId", envelope.correlationId().toString());
    MDC.put("eventId", envelope.eventId().toString());
    try {
      InboxEventId id = new InboxEventId(envelope.eventId(), CONSUMER_NAME);
      if (inboxEventRepository.existsById(id)) {
        log.info(
            "duplicate delivery of OrderPlaced for order {}, already processed, skipping",
            envelope.aggregateId());
        return;
      }

      orderPaymentContextRepository.save(parseContextOrFailNonRetryably(envelope));

      inboxEventRepository.save(new InboxEvent(id, envelope.eventType(), envelope.aggregateId()));
      log.info("recorded order payment context for order {}", envelope.aggregateId());
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

  /**
   * A malformed payload (missing/mistyped fields) will fail identically on every redelivery, so
   * it's wrapped as non-retryable and routed straight to the DLT instead of wasting the retry
   * budget on something that can never succeed.
   */
  private static OrderPaymentContext parseContextOrFailNonRetryably(EventEnvelope envelope) {
    try {
      JsonNode payload = envelope.payload();
      UUID customerId = UUID.fromString(payload.get("customerId").asString());
      JsonNode totalAmount = payload.get("totalAmount");
      BigDecimal amount = new BigDecimal(totalAmount.get("amount").asString());
      String currencyCode = totalAmount.get("currencyCode").asString();
      return new OrderPaymentContext(
          envelope.aggregateId(), customerId, amount, currencyCode, envelope.correlationId());
    } catch (RuntimeException malformed) {
      throw new NonRetryableEventProcessingException(
          "malformed OrderPlaced payload for event "
              + envelope.eventId()
              + ": "
              + malformed.getMessage());
    }
  }
}
