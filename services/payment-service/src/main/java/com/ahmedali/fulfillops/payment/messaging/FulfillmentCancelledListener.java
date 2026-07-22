package com.ahmedali.fulfillops.payment.messaging;

import com.ahmedali.fulfillops.payment.domain.RefundReasonCode;
import com.ahmedali.fulfillops.payment.service.RefundService;
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
 * Payment Service's own autonomous reaction to a fulfillment being cancelled — whether that
 * happened via a direct operator action on Fulfillment Service, or Fulfillment reacting to Order
 * Service's OrderCancellationRequested.v1: either way, refund whatever this order's payment still
 * has authorized. Ignores every other newStatus on this topic
 * (PICKING/PACKED/DISPATCHED/DELIVERED).
 */
@Component
public class FulfillmentCancelledListener {

  private static final Logger log = LoggerFactory.getLogger(FulfillmentCancelledListener.class);
  private static final String CONSUMER_NAME = "payment-service.fulfillment-cancelled";
  private static final String STATUS_CHANGED_EVENT_TYPE = "FulfillmentStatusChanged";
  private static final String CANCELLED_STATUS = "CANCELLED";

  private final InboxEventRepository inboxEventRepository;
  private final RefundService refundService;
  private final DeadLetterEventRecorder deadLetterEventRecorder;
  private final KafkaListenerMetrics metrics;
  private final ObjectMapper objectMapper;
  private final String topic;

  public FulfillmentCancelledListener(
      InboxEventRepository inboxEventRepository,
      RefundService refundService,
      DeadLetterEventRecorder deadLetterEventRecorder,
      KafkaListenerMetrics metrics,
      ObjectMapper objectMapper,
      @Value("${app.messaging.fulfillment-events-topic}") String topic) {
    this.inboxEventRepository = inboxEventRepository;
    this.refundService = refundService;
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
    if (!isFulfillmentCancelled(envelope)) {
      log.debug("ignoring event type={} on the fulfillment events topic", envelope.eventType());
      return;
    }

    MDC.put("correlationId", envelope.correlationId().toString());
    MDC.put("eventId", envelope.eventId().toString());
    MDC.put("aggregateId", envelope.aggregateId().toString());
    try {
      InboxEventId id = new InboxEventId(envelope.eventId(), CONSUMER_NAME);
      if (inboxEventRepository.existsById(id)) {
        log.info(
            "duplicate delivery of fulfillment cancellation for order {}, already processed, skipping",
            envelope.aggregateId());
        metrics.recordDuplicate(envelope.eventType());
        return;
      }

      try {
        refundService.refundForCompensation(
            envelope.aggregateId(),
            RefundReasonCode.FULFILLMENT_CANCELLED,
            envelope.correlationId());
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
      log.info("processed fulfillment cancellation for order {}", envelope.aggregateId());
    } finally {
      MDC.remove("correlationId");
      MDC.remove("eventId");
      MDC.remove("aggregateId");
    }
  }

  private static boolean isFulfillmentCancelled(EventEnvelope envelope) {
    if (!STATUS_CHANGED_EVENT_TYPE.equals(envelope.eventType())) {
      return false;
    }
    JsonNode newStatus = envelope.payload().get("newStatus");
    return newStatus != null && CANCELLED_STATUS.equals(newStatus.asString());
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
