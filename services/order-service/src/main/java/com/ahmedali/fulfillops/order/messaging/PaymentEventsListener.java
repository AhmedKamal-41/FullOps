package com.ahmedali.fulfillops.order.messaging;

import com.ahmedali.fulfillops.order.domain.OrderCancellationReasonCode;
import com.ahmedali.fulfillops.order.service.OperationsProjectionUpdater;
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
 * Order Service's own view of everything Payment Service reports: a payment authorized, a payment
 * declined (starts a cancellation — inventory still needs releasing, since it was reserved before
 * payment was ever attempted), or a refund confirming compensation for some cancellation already
 * tracked here.
 */
@Component
public class PaymentEventsListener {

  private static final Logger log = LoggerFactory.getLogger(PaymentEventsListener.class);
  private static final String CONSUMER_NAME = "order-service.payment-events";

  private final InboxEventRepository inboxEventRepository;
  private final OrderLifecycleTransaction lifecycleTransaction;
  private final OrderCancellationTransaction cancellationTransaction;
  private final OperationsProjectionUpdater projectionUpdater;
  private final DeadLetterEventRecorder deadLetterEventRecorder;
  private final ObjectMapper objectMapper;
  private final String topic;

  public PaymentEventsListener(
      InboxEventRepository inboxEventRepository,
      OrderLifecycleTransaction lifecycleTransaction,
      OrderCancellationTransaction cancellationTransaction,
      OperationsProjectionUpdater projectionUpdater,
      DeadLetterEventRecorder deadLetterEventRecorder,
      ObjectMapper objectMapper,
      @Value("${app.messaging.payment-events-topic}") String topic) {
    this.inboxEventRepository = inboxEventRepository;
    this.lifecycleTransaction = lifecycleTransaction;
    this.cancellationTransaction = cancellationTransaction;
    this.projectionUpdater = projectionUpdater;
    this.deadLetterEventRecorder = deadLetterEventRecorder;
    this.objectMapper = objectMapper;
    this.topic = topic;
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
    MDC.put("correlationId", envelope.correlationId().toString());
    MDC.put("eventId", envelope.eventId().toString());
    try {
      InboxEventId id = new InboxEventId(envelope.eventId(), CONSUMER_NAME);
      if (inboxEventRepository.existsById(id)) {
        log.info(
            "duplicate delivery of {} for order {}, already processed, skipping",
            envelope.eventType(),
            envelope.aggregateId());
        return;
      }

      dispatch(envelope);

      inboxEventRepository.save(new InboxEvent(id, envelope.eventType(), envelope.aggregateId()));
      log.info("processed {} for order {}", envelope.eventType(), envelope.aggregateId());
    } finally {
      MDC.remove("correlationId");
      MDC.remove("eventId");
    }
  }

  private void dispatch(EventEnvelope envelope) {
    switch (envelope.eventType()) {
      case "PaymentAuthorized" -> handlePaymentAuthorized(envelope);
      case "PaymentDeclined" -> handlePaymentDeclined(envelope);
      case "PaymentRefunded" ->
          cancellationTransaction.confirmPaymentRefund(
              envelope.aggregateId(), envelope.correlationId(), envelope.eventId());
      default ->
          log.debug(
              "ignoring unrecognized event type={} on the payment events topic",
              envelope.eventType());
    }
  }

  private void handlePaymentAuthorized(EventEnvelope envelope) {
    int technicalFailureCount = optionalInt(envelope.payload(), "precedingTechnicalFailureCount");
    projectionUpdater.recordPaymentOutcome(envelope.aggregateId(), null, technicalFailureCount);
    lifecycleTransaction.onPaymentAuthorized(envelope.aggregateId());
  }

  private void handlePaymentDeclined(EventEnvelope envelope) {
    String reasonDetail = optionalText(envelope.payload(), "reasonDetail");
    String reasonCode = optionalText(envelope.payload(), "reasonCode");
    int technicalFailureCount = optionalInt(envelope.payload(), "precedingTechnicalFailureCount");
    projectionUpdater.recordPaymentOutcome(
        envelope.aggregateId(), reasonCode, technicalFailureCount);
    cancellationTransaction.startOrMerge(
        envelope.aggregateId(),
        "system",
        reasonDetail,
        OrderCancellationReasonCode.PAYMENT_DECLINED,
        /* inventoryReleaseRequired= */ true,
        /* paymentRefundRequired= */ false,
        /* fulfillmentCancelRequired= */ false,
        envelope.correlationId(),
        envelope.eventId(),
        /* emitCancellationRequestedEvent= */ false);
  }

  private static String optionalText(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    return value == null ? null : value.asString();
  }

  private static int optionalInt(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    return value == null ? 0 : value.asInt();
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
