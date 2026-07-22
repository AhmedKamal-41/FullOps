package com.ahmedali.fulfillops.order.messaging;

import com.ahmedali.fulfillops.order.domain.LowStockSignal;
import com.ahmedali.fulfillops.order.domain.LowStockSignalRepository;
import com.ahmedali.fulfillops.order.domain.OrderCancelledReasonCode;
import com.ahmedali.fulfillops.order.service.OperationsProjectionUpdater;
import com.ahmedali.fulfillops.order.service.OrderCancellationTransaction;
import com.ahmedali.fulfillops.order.service.OrderLifecycleTransaction;
import java.time.Instant;
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
 * Order Service's own view of everything Inventory Service reports: stock reserved, stock rejected
 * (cancels the order immediately — nothing was ever reserved or charged), or a reservation released
 * as compensation for some cancellation already tracked here.
 */
@Component
public class InventoryEventsListener {

  private static final Logger log = LoggerFactory.getLogger(InventoryEventsListener.class);
  private static final String CONSUMER_NAME = "order-service.inventory-events";

  private final InboxEventRepository inboxEventRepository;
  private final OrderLifecycleTransaction lifecycleTransaction;
  private final OrderCancellationTransaction cancellationTransaction;
  private final OperationsProjectionUpdater projectionUpdater;
  private final LowStockSignalRepository lowStockSignalRepository;
  private final DeadLetterEventRecorder deadLetterEventRecorder;
  private final KafkaListenerMetrics metrics;
  private final ObjectMapper objectMapper;
  private final String topic;

  public InventoryEventsListener(
      InboxEventRepository inboxEventRepository,
      OrderLifecycleTransaction lifecycleTransaction,
      OrderCancellationTransaction cancellationTransaction,
      OperationsProjectionUpdater projectionUpdater,
      LowStockSignalRepository lowStockSignalRepository,
      DeadLetterEventRecorder deadLetterEventRecorder,
      KafkaListenerMetrics metrics,
      ObjectMapper objectMapper,
      @Value("${app.messaging.inventory-events-topic}") String topic) {
    this.inboxEventRepository = inboxEventRepository;
    this.lifecycleTransaction = lifecycleTransaction;
    this.cancellationTransaction = cancellationTransaction;
    this.projectionUpdater = projectionUpdater;
    this.lowStockSignalRepository = lowStockSignalRepository;
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
      topics = "${app.messaging.inventory-events-topic}",
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
      case "InventoryReserved" -> lifecycleTransaction.onInventoryReserved(envelope.aggregateId());
      case "InventoryRejected" -> handleInventoryRejected(envelope);
      case "InventoryReleased" ->
          cancellationTransaction.confirmInventoryRelease(
              envelope.aggregateId(), envelope.correlationId(), envelope.eventId());
      case "InventoryLowStock" -> handleLowStock(envelope);
      default ->
          log.debug(
              "ignoring unrecognized event type={} on the inventory events topic",
              envelope.eventType());
    }
  }

  private void handleInventoryRejected(EventEnvelope envelope) {
    String reasonDetail = optionalText(envelope.payload(), "reasonDetail");
    String reasonCode = optionalText(envelope.payload(), "reasonCode");
    projectionUpdater.recordInventoryRejection(envelope.aggregateId(), reasonCode);
    cancellationTransaction.finalizeDirectly(
        envelope.aggregateId(),
        OrderCancelledReasonCode.INVENTORY_REJECTED,
        reasonDetail,
        envelope.correlationId(),
        envelope.eventId());
  }

  // Not order-scoped (see contracts/README.md's aggregateId exception for this event) —
  // a plain upsert keyed by sku, independent of the inbox's per-order dedup semantics elsewhere
  // in this class, but still covered by the same (event_id, consumer_name) check in onMessage.
  private void handleLowStock(EventEnvelope envelope) {
    String sku = optionalText(envelope.payload(), "sku");
    int availableQuantity = optionalInt(envelope.payload(), "availableQuantity");
    int threshold = optionalInt(envelope.payload(), "threshold");
    boolean belowThreshold = envelope.payload().get("belowThreshold").asBoolean();
    Instant occurredAt = envelope.occurredAt();

    lowStockSignalRepository
        .findById(sku)
        .ifPresentOrElse(
            existing -> {
              existing.update(availableQuantity, threshold, belowThreshold, occurredAt);
              lowStockSignalRepository.save(existing);
            },
            () ->
                lowStockSignalRepository.save(
                    new LowStockSignal(
                        sku, availableQuantity, threshold, belowThreshold, occurredAt)));
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
    metrics.recordDeadLettered(envelope.eventType());
    log.error(
        "event routed to dead-letter topic after exhausting retries: type={} eventId={} orderId={}",
        envelope.eventType(),
        envelope.eventId(),
        envelope.aggregateId());
  }
}
