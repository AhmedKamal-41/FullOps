package com.ahmedali.fulfillops.fulfillment.messaging;

import com.ahmedali.fulfillops.fulfillment.service.FulfillmentTransition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Fulfillment Service's own autonomous reaction to Order Service's OrderCancellationRequested.v1:
 * cancel whatever this order's fulfillment still has, if it's still before dispatch — a no-op if
 * there is no fulfillment yet, it's already cancelled, or it's already too late (see
 * FulfillmentTransition.cancelForOrder).
 */
@Component
public class OrderCancellationRequestedListener {

  private static final Logger log =
      LoggerFactory.getLogger(OrderCancellationRequestedListener.class);
  private static final String CONSUMER_NAME = "fulfillment-service.order-cancellation-requested";
  private static final String CANCELLATION_REQUESTED_EVENT_TYPE = "OrderCancellationRequested";

  private final InboxEventRepository inboxEventRepository;
  private final FulfillmentTransition fulfillmentTransition;
  private final DeadLetterEventRecorder deadLetterEventRecorder;
  private final ObjectMapper objectMapper;
  private final String topic;

  public OrderCancellationRequestedListener(
      InboxEventRepository inboxEventRepository,
      FulfillmentTransition fulfillmentTransition,
      DeadLetterEventRecorder deadLetterEventRecorder,
      ObjectMapper objectMapper,
      @Value("${app.messaging.order-events-topic}") String topic) {
    this.inboxEventRepository = inboxEventRepository;
    this.fulfillmentTransition = fulfillmentTransition;
    this.deadLetterEventRecorder = deadLetterEventRecorder;
    this.objectMapper = objectMapper;
    this.topic = topic;
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
    if (!CANCELLATION_REQUESTED_EVENT_TYPE.equals(envelope.eventType())) {
      log.debug("ignoring event type={} on the order events topic", envelope.eventType());
      return;
    }

    MDC.put("correlationId", envelope.correlationId().toString());
    MDC.put("eventId", envelope.eventId().toString());
    try {
      InboxEventId id = new InboxEventId(envelope.eventId(), CONSUMER_NAME);
      if (inboxEventRepository.existsById(id)) {
        log.info(
            "duplicate delivery of OrderCancellationRequested for order {}, already processed, skipping",
            envelope.aggregateId());
        return;
      }

      String reasonDetail = optionalText(envelope.payload(), "reasonDetail");
      fulfillmentTransition.cancelForOrder(
          envelope.aggregateId(), reasonDetail, envelope.correlationId());

      inboxEventRepository.save(new InboxEvent(id, envelope.eventType(), envelope.aggregateId()));
      log.info("processed OrderCancellationRequested for order {}", envelope.aggregateId());
    } finally {
      MDC.remove("correlationId");
      MDC.remove("eventId");
    }
  }

  private static String optionalText(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    return value == null ? null : value.asString();
  }

  @DltHandler
  public void onDlt(String envelopeJson) {
    EventEnvelope envelope = objectMapper.readValue(envelopeJson, EventEnvelope.class);
    deadLetterEventRecorder.record(envelope, CONSUMER_NAME, topic, envelopeJson);
    log.error(
        "event routed to dead-letter topic after exhausting retries: type={} eventId={} orderId={}",
        envelope.eventType(),
        envelope.eventId(),
        envelope.aggregateId());
  }
}
