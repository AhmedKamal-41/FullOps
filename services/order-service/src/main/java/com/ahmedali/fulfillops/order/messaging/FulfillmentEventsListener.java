package com.ahmedali.fulfillops.order.messaging;

import com.ahmedali.fulfillops.order.domain.OrderCancellationReasonCode;
import com.ahmedali.fulfillops.order.domain.OrderStatus;
import com.ahmedali.fulfillops.order.service.OrderCancellationTransaction;
import com.ahmedali.fulfillops.order.service.OrderLifecycleTransaction;
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
 * Order Service's own view of everything Fulfillment Service reports: a fulfillment created, each
 * warehouse status step, or a fulfillment cancellation — which always starts or confirms a
 * cancellation (whatever caused it: a direct operator action on Fulfillment Service, or Fulfillment
 * reacting to our own OrderCancellationRequested.v1) rather than advancing the order forward.
 */
@Component
public class FulfillmentEventsListener {

  private static final Logger log = LoggerFactory.getLogger(FulfillmentEventsListener.class);
  private static final String CONSUMER_NAME = "order-service.fulfillment-events";
  private static final String CANCELLED_STATUS = "CANCELLED";

  private final InboxEventRepository inboxEventRepository;
  private final OrderLifecycleTransaction lifecycleTransaction;
  private final OrderCancellationTransaction cancellationTransaction;
  private final DeadLetterEventRecorder deadLetterEventRecorder;
  private final KafkaListenerMetrics metrics;
  private final ObjectMapper objectMapper;
  private final String topic;

  public FulfillmentEventsListener(
      InboxEventRepository inboxEventRepository,
      OrderLifecycleTransaction lifecycleTransaction,
      OrderCancellationTransaction cancellationTransaction,
      DeadLetterEventRecorder deadLetterEventRecorder,
      KafkaListenerMetrics metrics,
      ObjectMapper objectMapper,
      @Value("${app.messaging.fulfillment-events-topic}") String topic) {
    this.inboxEventRepository = inboxEventRepository;
    this.lifecycleTransaction = lifecycleTransaction;
    this.cancellationTransaction = cancellationTransaction;
    this.deadLetterEventRecorder = deadLetterEventRecorder;
    this.metrics = metrics;
    this.objectMapper = objectMapper;
    this.topic = topic;
  }

  @RetryableTopic(
      attempts = "4",
      backOff = @BackOff(delay = 500, multiplier = 2.0, maxDelay = 5000),
      exclude = NonRetryableEventProcessingException.class)
  @KafkaListener(
      topics = "${app.messaging.fulfillment-events-topic}",
      groupId = "${spring.application.name}")
  @Transactional
  public void onMessage(String envelopeJson) {
    EventEnvelope envelope = objectMapper.readValue(envelopeJson, EventEnvelope.class);
    MDC.put("correlationId", envelope.correlationId().toString());
    MDC.put("eventId", envelope.eventId().toString());
    MDC.put("aggregateId", envelope.aggregateId().toString());
    try {
      InboxEventId id = new InboxEventId(envelope.eventId(), CONSUMER_NAME);
      if (inboxEventRepository.existsById(id)) {
        log.info(
            "duplicate delivery of {} for order {}, already processed, skipping",
            envelope.eventType(),
            envelope.aggregateId());
        metrics.recordDuplicate(envelope.eventType());
        return;
      }

      try {
        dispatch(envelope);
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
      log.info("processed {} for order {}", envelope.eventType(), envelope.aggregateId());
    } finally {
      MDC.remove("correlationId");
      MDC.remove("eventId");
      MDC.remove("aggregateId");
    }
  }

  private void dispatch(EventEnvelope envelope) {
    switch (envelope.eventType()) {
      case "FulfillmentAssigned" ->
          lifecycleTransaction.onFulfillmentAssigned(envelope.aggregateId());
      case "FulfillmentStatusChanged" -> handleStatusChanged(envelope);
      default ->
          log.debug(
              "ignoring unrecognized event type={} on the fulfillment events topic",
              envelope.eventType());
    }
  }

  private void handleStatusChanged(EventEnvelope envelope) {
    String newStatus = requiredText(envelope.payload(), "newStatus", envelope);
    if (CANCELLED_STATUS.equals(newStatus)) {
      handleFulfillmentCancelled(envelope);
      return;
    }
    lifecycleTransaction.onFulfillmentStatusChanged(
        envelope.aggregateId(), OrderStatus.valueOf(newStatus));
  }

  private void handleFulfillmentCancelled(EventEnvelope envelope) {
    // A fulfillment cancellation always implies inventory was reserved and payment was authorized
    // (fulfillment is only ever created after both), so both are required compensations here.
    // Whether the tracker already exists (this order's own OrderCancellationRequested.v1 caused
    // this) or not (a direct operator action on Fulfillment Service caused it) is handled the same
    // way: ensure it exists, then confirm the fulfillment side is done.
    cancellationTransaction.startOrMerge(
        envelope.aggregateId(),
        "system",
        null,
        OrderCancellationReasonCode.FULFILLMENT_CANCELLED,
        /* inventoryReleaseRequired= */ true,
        /* paymentRefundRequired= */ true,
        /* fulfillmentCancelRequired= */ false,
        envelope.correlationId(),
        envelope.eventId(),
        /* emitCancellationRequestedEvent= */ false);
    cancellationTransaction.confirmFulfillmentCancel(
        envelope.aggregateId(), envelope.correlationId(), envelope.eventId());
  }

  private static String requiredText(JsonNode payload, String field, EventEnvelope envelope) {
    JsonNode value = payload.get(field);
    if (value == null) {
      throw new NonRetryableEventProcessingException(
          "malformed "
              + envelope.eventType()
              + " payload for event "
              + envelope.eventId()
              + ": missing "
              + field);
    }
    return value.asString();
  }

  @DltHandler
  public void onDlt(String envelopeJson) {
    EventEnvelope envelope = objectMapper.readValue(envelopeJson, EventEnvelope.class);
    deadLetterEventRecorder.record(envelope, CONSUMER_NAME, topic, envelopeJson);
    metrics.recordDeadLettered(envelope.eventType());
    log.error(
        "event routed to dead-letter topic after exhausting retries: type={} eventId={} orderId={}",
        envelope.eventType(),
        envelope.eventId(),
        envelope.aggregateId());
  }
}
