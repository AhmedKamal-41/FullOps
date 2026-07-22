package com.ahmedali.fulfillops.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ahmedali.fulfillops.order.config.TestSecurityConfig;
import com.ahmedali.fulfillops.order.config.TestcontainersConfiguration;
import com.ahmedali.fulfillops.order.domain.IncidentKind;
import com.ahmedali.fulfillops.order.domain.IncidentStatus;
import com.ahmedali.fulfillops.order.domain.OperationsIncidentRepository;
import com.ahmedali.fulfillops.order.domain.Order;
import com.ahmedali.fulfillops.order.domain.OrderCancellationRepository;
import com.ahmedali.fulfillops.order.domain.OrderOperationsProjection;
import com.ahmedali.fulfillops.order.domain.OrderOperationsProjectionRepository;
import com.ahmedali.fulfillops.order.domain.OrderRepository;
import com.ahmedali.fulfillops.order.domain.OrderStatus;
import com.ahmedali.fulfillops.order.messaging.FulfillmentEventsListener;
import com.ahmedali.fulfillops.order.messaging.InventoryEventsListener;
import com.ahmedali.fulfillops.order.messaging.OutboxEvent;
import com.ahmedali.fulfillops.order.messaging.OutboxEventRepository;
import com.ahmedali.fulfillops.order.messaging.PaymentEventsListener;
import com.ahmedali.fulfillops.order.service.OrderCancellationService;
import com.ahmedali.fulfillops.order.service.OrderMilestoneTooEarlyException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives the four new lifecycle listeners and OrderCancellationService directly against a full
 * application context backed by Testcontainers Postgres/Kafka/Redis, covering:
 * happy path to DELIVERED, inventory rejection, payment decline + release, duplicate and
 * out-of-order events, and cancellation before/after authorization and after dispatch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class CancellationSagaIT {

  @Autowired private InventoryEventsListener inventoryEventsListener;
  @Autowired private PaymentEventsListener paymentEventsListener;
  @Autowired private FulfillmentEventsListener fulfillmentEventsListener;
  @Autowired private OrderCancellationService orderCancellationService;
  @Autowired private OrderRepository orderRepository;
  @Autowired private OrderOperationsProjectionRepository projectionRepository;
  @Autowired private OrderCancellationRepository cancellationRepository;
  @Autowired private OperationsIncidentRepository incidentRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void happyPathReachesDelivered() {
    Order order = seedOrder(OrderStatus.PENDING);

    inventoryEventsListener.onMessage(envelope("InventoryReserved", order.getOrderId(), Map.of()));
    assertStatus(order.getOrderId(), OrderStatus.INVENTORY_RESERVED);

    paymentEventsListener.onMessage(envelope("PaymentAuthorized", order.getOrderId(), Map.of()));
    assertStatus(order.getOrderId(), OrderStatus.PAYMENT_AUTHORIZED);

    fulfillmentEventsListener.onMessage(
        envelope("FulfillmentAssigned", order.getOrderId(), Map.of()));
    assertStatus(order.getOrderId(), OrderStatus.FULFILLMENT_ASSIGNED);

    advanceFulfillment(order.getOrderId(), "PICKING");
    assertStatus(order.getOrderId(), OrderStatus.PICKING);
    advanceFulfillment(order.getOrderId(), "PACKED");
    assertStatus(order.getOrderId(), OrderStatus.PACKED);
    advanceFulfillment(order.getOrderId(), "DISPATCHED");
    assertStatus(order.getOrderId(), OrderStatus.DISPATCHED);
    advanceFulfillment(order.getOrderId(), "DELIVERED");
    assertStatus(order.getOrderId(), OrderStatus.DELIVERED);
  }

  @Test
  void inventoryRejectionCancelsImmediatelyWithoutInvokingPayment() {
    Order order = seedOrder(OrderStatus.PENDING);

    inventoryEventsListener.onMessage(
        envelope(
            "InventoryRejected",
            order.getOrderId(),
            Map.of(
                "items",
                java.util.List.of(
                    Map.of("sku", "WIDGET-1", "requestedQuantity", 5, "availableQuantity", 1)),
                "reasonCode",
                "INSUFFICIENT_STOCK")));

    assertStatus(order.getOrderId(), OrderStatus.CANCELLED);
    assertThat(outboxEventFor(order.getOrderId(), "OrderCancelled")).isPresent();
    assertThat(cancellationRepository.findById(order.getOrderId())).isEmpty();
  }

  @Test
  void paymentDeclineWaitsForInventoryReleaseBeforeCancelling() {
    Order order = seedOrder(OrderStatus.INVENTORY_RESERVED);

    paymentEventsListener.onMessage(
        envelope(
            "PaymentDeclined",
            order.getOrderId(),
            Map.of(
                "amount",
                Map.of("currencyCode", "USD", "amount", "10.00"),
                "reasonCode",
                "SIMULATED_CARD_DECLINED")));
    assertStatus(order.getOrderId(), OrderStatus.CANCELLATION_PENDING);

    inventoryEventsListener.onMessage(
        envelope(
            "InventoryReleased",
            order.getOrderId(),
            Map.of(
                "reservationId",
                UUID.randomUUID().toString(),
                "items",
                java.util.List.of(Map.of("sku", "WIDGET-1", "quantity", 1)),
                "reasonCode",
                "PAYMENT_DECLINED")));
    assertStatus(order.getOrderId(), OrderStatus.CANCELLED);
  }

  @Test
  void aSecondDeliveryOfTheSameEventIsANoOp() {
    Order order = seedOrder(OrderStatus.PENDING);
    UUID eventId = UUID.randomUUID();
    String json = envelope(eventId, "InventoryReserved", order.getOrderId(), Map.of());

    inventoryEventsListener.onMessage(json);
    inventoryEventsListener.onMessage(json);

    assertStatus(order.getOrderId(), OrderStatus.INVENTORY_RESERVED);
  }

  @Test
  void anOutOfOrderEventIsRetriedRatherThanSilentlyDropped() {
    Order order = seedOrder(OrderStatus.PENDING);

    assertThatThrownBy(
            () ->
                paymentEventsListener.onMessage(
                    envelope("PaymentAuthorized", order.getOrderId(), Map.of())))
        .isInstanceOf(OrderMilestoneTooEarlyException.class);
    assertStatus(order.getOrderId(), OrderStatus.PENDING);
  }

  @Test
  void cancellationBeforeAnythingWasReservedFinalizesImmediately() {
    Order order = seedOrder(OrderStatus.PENDING);

    orderCancellationService.requestCancellation(
        order.getOrderId(),
        order.getCustomerId().toString(),
        false,
        "cancel-1",
        null,
        UUID.randomUUID());

    assertStatus(order.getOrderId(), OrderStatus.CANCELLED);
  }

  @Test
  void cancellationAfterAuthorizationWaitsForEveryRequiredConfirmation() {
    Order order = seedOrder(OrderStatus.PAYMENT_AUTHORIZED);

    orderCancellationService.requestCancellation(
        order.getOrderId(),
        order.getCustomerId().toString(),
        false,
        "cancel-2",
        null,
        UUID.randomUUID());
    assertStatus(order.getOrderId(), OrderStatus.CANCELLATION_PENDING);
    assertThat(outboxEventFor(order.getOrderId(), "OrderCancellationRequested")).isPresent();

    inventoryEventsListener.onMessage(
        envelope(
            "InventoryReleased",
            order.getOrderId(),
            Map.of(
                "reservationId",
                UUID.randomUUID().toString(),
                "items",
                java.util.List.of(Map.of("sku", "WIDGET-1", "quantity", 1)),
                "reasonCode",
                "ORDER_CANCELLED")));
    assertStatus(order.getOrderId(), OrderStatus.CANCELLATION_PENDING);

    paymentEventsListener.onMessage(
        envelope(
            "PaymentRefunded",
            order.getOrderId(),
            Map.of(
                "paymentId",
                UUID.randomUUID().toString(),
                "amount",
                Map.of("currencyCode", "USD", "amount", "10.00"),
                "reasonCode",
                "ORDER_CANCELLED")));
    assertStatus(order.getOrderId(), OrderStatus.CANCELLED);
  }

  @Test
  void cancellationRequestedAfterDispatchEscalatesToRequiresReview() {
    Order order = seedOrder(OrderStatus.DISPATCHED);

    orderCancellationService.requestCancellation(
        order.getOrderId(),
        order.getCustomerId().toString(),
        false,
        "cancel-3",
        null,
        UUID.randomUUID());

    assertStatus(order.getOrderId(), OrderStatus.REQUIRES_REVIEW);
    assertThat(
            incidentRepository.findByOrderIdAndKindAndStatus(
                order.getOrderId(), IncidentKind.CANCELLATION_AFTER_DISPATCH, IncidentStatus.OPEN))
        .isPresent();
  }

  private void advanceFulfillment(UUID orderId, String newStatus) {
    fulfillmentEventsListener.onMessage(
        envelope("FulfillmentStatusChanged", orderId, Map.of("newStatus", newStatus)));
  }

  private void assertStatus(UUID orderId, OrderStatus expected) {
    assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(expected);
  }

  private java.util.Optional<OutboxEvent> outboxEventFor(UUID orderId, String eventType) {
    return outboxEventRepository.findAll().stream()
        .filter(row -> row.getAggregateId().equals(orderId) && row.getEventType().equals(eventType))
        .findFirst();
  }

  private Order seedOrder(OrderStatus status) {
    Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "USD", new BigDecimal("10.00"));
    order.updateStatus(status);
    orderRepository.save(order);
    // advanceStage assumes onOrderPlaced already created this row, exactly like production's
    // OrderCreationTransaction always does — seed it directly here since this test bypasses that.
    projectionRepository.save(
        new OrderOperationsProjection(
            order.getOrderId(),
            order.getCustomerId(),
            status,
            order.getCurrencyCode(),
            order.getTotalAmount(),
            order.getCreatedAt()));
    return order;
  }

  private String envelope(String eventType, UUID orderId, Map<String, Object> payload) {
    return envelope(UUID.randomUUID(), eventType, orderId, payload);
  }

  private String envelope(
      UUID eventId, String eventType, UUID orderId, Map<String, Object> payload) {
    var envelope =
        new com.ahmedali.fulfillops.order.messaging.EventEnvelope(
            eventId,
            eventType,
            1,
            Instant.now(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            orderId,
            "test-producer",
            objectMapper.valueToTree(payload));
    return objectMapper.writeValueAsString(envelope);
  }
}
