package com.ahmedali.fulfillops.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ahmedali.fulfillops.order.config.TestSecurityConfig;
import com.ahmedali.fulfillops.order.config.TestcontainersConfiguration;
import com.ahmedali.fulfillops.order.domain.IncidentKind;
import com.ahmedali.fulfillops.order.domain.Order;
import com.ahmedali.fulfillops.order.domain.OrderOperationsProjection;
import com.ahmedali.fulfillops.order.domain.OrderOperationsProjectionRepository;
import com.ahmedali.fulfillops.order.domain.OrderStageDuration;
import com.ahmedali.fulfillops.order.domain.OrderStageDurationRepository;
import com.ahmedali.fulfillops.order.domain.ProjectionRebuildStatus;
import com.ahmedali.fulfillops.order.messaging.EventEnvelope;
import com.ahmedali.fulfillops.order.messaging.FulfillmentEventsListener;
import com.ahmedali.fulfillops.order.messaging.InventoryEventsListener;
import com.ahmedali.fulfillops.order.messaging.PaymentEventsListener;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * The core "done when" criterion for projection rebuild: build the projection through the real
 * event-driven path (happy path to DELIVERED, an inventory rejection, a payment decline that
 * cancels, and an incident), snapshot it, rebuild it from scratch, and assert the rebuilt tables
 * are identical — proving rebuild is a real reconstruction of this service's own durable history,
 * not a best-effort approximation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class OperationsProjectionRebuildIT {

  @Autowired private OrderCreationTransaction orderCreationTransaction;
  @Autowired private InventoryEventsListener inventoryEventsListener;
  @Autowired private PaymentEventsListener paymentEventsListener;
  @Autowired private FulfillmentEventsListener fulfillmentEventsListener;
  @Autowired private IncidentService incidentService;
  @Autowired private OperationsProjectionRebuildService rebuildService;
  @Autowired private OrderOperationsProjectionRepository projectionRepository;
  @Autowired private OrderStageDurationRepository stageDurationRepository;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void rebuildingAfterAVarietyOfLifecyclesReproducesTheLiveBuiltProjectionExactly() {
    Order deliveredOrder = placeOrder();
    driveToDelivered(deliveredOrder.getOrderId());

    Order rejectedOrder = placeOrder();
    rejectInventory(rejectedOrder.getOrderId());

    Order declinedOrder = placeOrder();
    declinePayment(declinedOrder.getOrderId());

    incidentService.openOrDeduplicate(
        deliveredOrder.getOrderId(), IncidentKind.CANCELLATION_AFTER_DISPATCH, "test incident");

    List<UUID> orderIds =
        List.of(
            deliveredOrder.getOrderId(), rejectedOrder.getOrderId(), declinedOrder.getOrderId());
    Map<UUID, ProjectionSnapshot> before = snapshot(orderIds);

    ProjectionRebuildStatus status = rebuildService.rebuild("test-admin").getStatus();

    assertThat(status).isEqualTo(ProjectionRebuildStatus.COMPLETED);
    Map<UUID, ProjectionSnapshot> after = snapshot(orderIds);
    assertThat(after).isEqualTo(before);
  }

  private Order placeOrder() {
    OrderPricing.Computed computed =
        new OrderPricing.Computed(
            List.of(
                new OrderPricing.ComputedItem(
                    "SKU-REBUILD",
                    1,
                    new java.math.BigDecimal("10.00"),
                    new java.math.BigDecimal("10.00"))),
            "USD",
            new java.math.BigDecimal("10.00"));
    return orderCreationTransaction.createNewOrder(
        UUID.randomUUID(),
        "rebuild-test-" + UUID.randomUUID(),
        computed,
        UUID.randomUUID().toString(),
        UUID.randomUUID());
  }

  private void driveToDelivered(UUID orderId) {
    inventoryEventsListener.onMessage(envelope("InventoryReserved", orderId, Map.of()));
    paymentEventsListener.onMessage(envelope("PaymentAuthorized", orderId, Map.of()));
    fulfillmentEventsListener.onMessage(envelope("FulfillmentAssigned", orderId, Map.of()));
    for (String status : List.of("PICKING", "PACKED", "DISPATCHED", "DELIVERED")) {
      fulfillmentEventsListener.onMessage(
          envelope("FulfillmentStatusChanged", orderId, Map.of("newStatus", status)));
    }
  }

  private void rejectInventory(UUID orderId) {
    inventoryEventsListener.onMessage(
        envelope(
            "InventoryRejected",
            orderId,
            Map.of(
                "items",
                List.of(
                    Map.of("sku", "SKU-REBUILD", "requestedQuantity", 1, "availableQuantity", 0)),
                "reasonCode",
                "INSUFFICIENT_STOCK")));
  }

  private void declinePayment(UUID orderId) {
    inventoryEventsListener.onMessage(envelope("InventoryReserved", orderId, Map.of()));
    paymentEventsListener.onMessage(
        envelope(
            "PaymentDeclined",
            orderId,
            Map.of(
                "amount",
                Map.of("currencyCode", "USD", "amount", "10.00"),
                "reasonCode",
                "SIMULATED_INSUFFICIENT_FUNDS",
                "precedingTechnicalFailureCount",
                2)));
    inventoryEventsListener.onMessage(envelope("InventoryReleased", orderId, Map.of()));
  }

  private Map<UUID, ProjectionSnapshot> snapshot(List<UUID> orderIds) {
    return orderIds.stream().collect(Collectors.toMap(id -> id, this::snapshotOf));
  }

  private ProjectionSnapshot snapshotOf(UUID orderId) {
    OrderOperationsProjection projection = projectionRepository.findById(orderId).orElseThrow();
    List<OrderStageDuration> stages =
        stageDurationRepository.findAll().stream()
            .filter(row -> row.getOrderId().equals(orderId))
            .sorted(Comparator.comparing(OrderStageDuration::getEnteredAt))
            .toList();
    List<StageSnapshot> stageSnapshots =
        stages.stream()
            .map(
                s ->
                    new StageSnapshot(
                        s.getStage().name(),
                        s.getEnteredAt(),
                        s.getExitedAt(),
                        s.getDurationSeconds()))
            .toList();
    return new ProjectionSnapshot(
        projection.getStatus().name(),
        projection.getCustomerId(),
        projection.getCurrencyCode(),
        projection.getTotalAmount(),
        projection.getCreatedAt(),
        projection.getCurrentStageEnteredAt(),
        projection.getInventoryRejectionReasonCode(),
        projection.getPaymentDeclineReasonCode(),
        projection.getPaymentTechnicalFailureCount(),
        projection.getCancellationReasonCode(),
        projection.getRequiresReviewReasonCode(),
        projection.getOpenIncidentCount(),
        stageSnapshots);
  }

  private String envelope(String eventType, UUID orderId, Map<String, Object> payload) {
    EventEnvelope envelope =
        new EventEnvelope(
            UUID.randomUUID(),
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

  // version is deliberately excluded — rebuild starts every optimistic-lock counter fresh at 0,
  // which is correct (not a "result" fact worth comparing), not a rebuild defect.
  private record ProjectionSnapshot(
      String status,
      UUID customerId,
      String currencyCode,
      java.math.BigDecimal totalAmount,
      Instant createdAt,
      Instant currentStageEnteredAt,
      String inventoryRejectionReasonCode,
      String paymentDeclineReasonCode,
      int paymentTechnicalFailureCount,
      String cancellationReasonCode,
      String requiresReviewReasonCode,
      int openIncidentCount,
      List<StageSnapshot> stages) {}

  private record StageSnapshot(
      String stage, Instant enteredAt, Instant exitedAt, Long durationSeconds) {}
}
