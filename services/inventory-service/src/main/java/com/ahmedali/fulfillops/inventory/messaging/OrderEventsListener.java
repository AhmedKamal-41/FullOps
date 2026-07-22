package com.ahmedali.fulfillops.inventory.messaging;

import com.ahmedali.fulfillops.inventory.domain.InventoryReservationRepository;
import com.ahmedali.fulfillops.inventory.service.ReleaseReasonCode;
import com.ahmedali.fulfillops.inventory.service.RequestedItem;
import com.ahmedali.fulfillops.inventory.service.ReservationOutcome;
import com.ahmedali.fulfillops.inventory.service.ReservationReleaseService;
import com.ahmedali.fulfillops.inventory.service.ReservationService;
import java.util.ArrayList;
import java.util.List;
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
 * Inventory Service's view of everything order-service reports on its own topic: OrderPlaced
 * (reserve stock) and OrderCancellationRequested (release whatever reservation this
 * order still has — a no-op if there is none, e.g. cancellation was requested before stock was ever
 * reserved). Both event types share one consumer group on this topic, since Kafka only delivers
 * each message to one consumer per group — two separate listener classes here would silently split
 * the traffic between them instead of each seeing every message.
 */
@Component
public class OrderEventsListener {

  private static final Logger log = LoggerFactory.getLogger(OrderEventsListener.class);
  private static final String CONSUMER_NAME = "inventory-service.order-events";
  private static final String ORDER_PLACED_EVENT_TYPE = "OrderPlaced";
  private static final String CANCELLATION_REQUESTED_EVENT_TYPE = "OrderCancellationRequested";

  private final InboxEventRepository inboxEventRepository;
  private final ReservationService reservationService;
  private final ReservationReleaseService reservationReleaseService;
  private final InventoryReservationRepository reservationRepository;
  private final DeadLetterEventRecorder deadLetterEventRecorder;
  private final KafkaListenerMetrics metrics;
  private final ObjectMapper objectMapper;
  private final String topic;

  public OrderEventsListener(
      InboxEventRepository inboxEventRepository,
      ReservationService reservationService,
      ReservationReleaseService reservationReleaseService,
      InventoryReservationRepository reservationRepository,
      DeadLetterEventRecorder deadLetterEventRecorder,
      KafkaListenerMetrics metrics,
      ObjectMapper objectMapper,
      @Value("${app.messaging.order-events-topic}") String topic) {
    this.inboxEventRepository = inboxEventRepository;
    this.reservationService = reservationService;
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
      topics = "${app.messaging.order-events-topic}",
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
      case ORDER_PLACED_EVENT_TYPE -> handleOrderPlaced(envelope);
      case CANCELLATION_REQUESTED_EVENT_TYPE -> handleCancellationRequested(envelope);
      default ->
          log.debug(
              "ignoring unrecognized event type={} on the order events topic",
              envelope.eventType());
    }
  }

  private void handleOrderPlaced(EventEnvelope envelope) {
    List<RequestedItem> items = parseItemsOrFailNonRetryably(envelope);
    ReservationOutcome outcome =
        reservationService.reserve(
            envelope.aggregateId(), items, envelope.correlationId(), envelope.eventId());
    log.info(
        "reservation outcome for order {}: {}",
        envelope.aggregateId(),
        outcome.getClass().getSimpleName());
  }

  private void handleCancellationRequested(EventEnvelope envelope) {
    if (reservationRepository.findByOrderId(envelope.aggregateId()).isEmpty()) {
      log.info("no reservation exists for order {}, nothing to release", envelope.aggregateId());
      return;
    }
    reservationReleaseService.release(
        envelope.aggregateId(),
        ReleaseReasonCode.ORDER_CANCELLED,
        envelope.correlationId(),
        envelope.eventId());
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

  /**
   * A malformed payload (missing/mistyped fields) will fail identically on every redelivery, so
   * it's wrapped as non-retryable and routed straight to the DLT instead of wasting the retry
   * budget on something that can never succeed.
   */
  private static List<RequestedItem> parseItemsOrFailNonRetryably(EventEnvelope envelope) {
    try {
      List<RequestedItem> items = new ArrayList<>();
      for (JsonNode itemNode : envelope.payload().get("items")) {
        items.add(
            new RequestedItem(itemNode.get("sku").asString(), itemNode.get("quantity").asInt()));
      }
      if (items.isEmpty()) {
        throw new IllegalArgumentException("items must not be empty");
      }
      return items;
    } catch (RuntimeException malformed) {
      throw new NonRetryableEventProcessingException(
          "malformed OrderPlaced payload for event "
              + envelope.eventId()
              + ": "
              + malformed.getMessage());
    }
  }
}
