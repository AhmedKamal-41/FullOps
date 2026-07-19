package com.ahmedali.fulfillops.inventory.messaging;

import com.ahmedali.fulfillops.inventory.service.RequestedItem;
import com.ahmedali.fulfillops.inventory.service.ReservationOutcome;
import com.ahmedali.fulfillops.inventory.service.ReservationService;
import java.util.ArrayList;
import java.util.List;
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
 * Inventory Service's first genuine cross-service consumer: reacts to order-service's own
 * OrderPlaced.v1 events on its outbox topic. Same check-then-process-then-record inbox shape as
 * every other listener in this codebase (see docs/adr/0003-outbox-inbox.md) — what's new here is
 * real domain effect instead of a scaffold. Business rejections (insufficient stock) are handled
 * entirely inside ReservationService/ReservationTransaction and never reach this class as an
 * exception; only a genuine concurrency-retry exhaustion (StockConcurrencyException) propagates, so
 * the existing @RetryableTopic mechanism gets a later, less contended, chance.
 */
@Component
public class OrderPlacedListener {

  private static final Logger log = LoggerFactory.getLogger(OrderPlacedListener.class);
  private static final String CONSUMER_NAME = "inventory-service.order-placed";
  private static final String ORDER_PLACED_EVENT_TYPE = "OrderPlaced";

  private final InboxEventRepository inboxEventRepository;
  private final ReservationService reservationService;
  private final ObjectMapper objectMapper;

  public OrderPlacedListener(
      InboxEventRepository inboxEventRepository,
      ReservationService reservationService,
      ObjectMapper objectMapper) {
    this.inboxEventRepository = inboxEventRepository;
    this.reservationService = reservationService;
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
      // OrderRequiresReview.v1, ...) that Inventory has no reason to react to.
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

      List<RequestedItem> items = parseItemsOrFailNonRetryably(envelope);
      ReservationOutcome outcome =
          reservationService.reserve(
              envelope.aggregateId(), items, envelope.correlationId(), envelope.eventId());

      inboxEventRepository.save(new InboxEvent(id, envelope.eventType(), envelope.aggregateId()));
      log.info(
          "processed OrderPlaced for order {}, outcome={}",
          envelope.aggregateId(),
          outcome.getClass().getSimpleName());
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
