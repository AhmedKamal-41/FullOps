package com.ahmedali.fulfillops.inventory.messaging;

import com.ahmedali.fulfillops.inventory.domain.InventoryReservationRepository;
import com.ahmedali.fulfillops.inventory.service.ReleaseReasonCode;
import com.ahmedali.fulfillops.inventory.service.ReservationReleaseService;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Inventory Service's own autonomous reaction to a declined payment: release whatever this order
 * still has reserved. Order Service separately, independently reacts to the same PaymentDeclined.v1
 * to move the order toward CANCELLED once it sees the InventoryReleased.v1 this produces — no
 * command travels from Order Service to here, each service just reacts to the fact itself.
 */
@Component
public class PaymentDeclinedListener {

  private static final Logger log = LoggerFactory.getLogger(PaymentDeclinedListener.class);
  private static final String CONSUMER_NAME = "inventory-service.payment-declined";
  private static final String PAYMENT_DECLINED_EVENT_TYPE = "PaymentDeclined";

  private final InboxEventRepository inboxEventRepository;
  private final ReservationReleaseService reservationReleaseService;
  private final InventoryReservationRepository reservationRepository;
  private final DeadLetterEventRecorder deadLetterEventRecorder;
  private final KafkaListenerMetrics metrics;
  private final ObjectMapper objectMapper;
  private final String topic;

  public PaymentDeclinedListener(
      InboxEventRepository inboxEventRepository,
      ReservationReleaseService reservationReleaseService,
      InventoryReservationRepository reservationRepository,
      DeadLetterEventRecorder deadLetterEventRecorder,
      KafkaListenerMetrics metrics,
      ObjectMapper objectMapper,
      @Value("${app.messaging.payment-events-topic}") String topic) {
    this.inboxEventRepository = inboxEventRepository;
    this.reservationReleaseService = reservationReleaseService;
    this.reservationRepository = reservationRepository;
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
      topics = "${app.messaging.payment-events-topic}",
      groupId = "${spring.application.name}")
  @Transactional
  public void onMessage(String envelopeJson) {
    EventEnvelope envelope = objectMapper.readValue(envelopeJson, EventEnvelope.class);
    if (!PAYMENT_DECLINED_EVENT_TYPE.equals(envelope.eventType())) {
      log.debug("ignoring event type={} on the payment events topic", envelope.eventType());
      return;
    }

    MDC.put("correlationId", envelope.correlationId().toString());
    MDC.put("eventId", envelope.eventId().toString());
    MDC.put("aggregateId", envelope.aggregateId().toString());
    try {
      InboxEventId id = new InboxEventId(envelope.eventId(), CONSUMER_NAME);
      if (inboxEventRepository.existsById(id)) {
        log.info(
            "duplicate delivery of PaymentDeclined for order {}, already processed, skipping",
            envelope.aggregateId());
        metrics.recordDuplicate(envelope.eventType());
        return;
      }

      try {
        if (reservationRepository.findByOrderId(envelope.aggregateId()).isPresent()) {
          reservationReleaseService.release(
              envelope.aggregateId(),
              ReleaseReasonCode.PAYMENT_DECLINED,
              envelope.correlationId(),
              envelope.eventId());
        } else {
          log.info(
              "no reservation exists for order {}, nothing to release", envelope.aggregateId());
        }
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
      log.info("processed PaymentDeclined for order {}", envelope.aggregateId());
    } finally {
      MDC.remove("correlationId");
      MDC.remove("eventId");
      MDC.remove("aggregateId");
    }
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
