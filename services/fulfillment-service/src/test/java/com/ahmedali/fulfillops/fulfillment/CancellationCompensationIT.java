package com.ahmedali.fulfillops.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import com.ahmedali.fulfillops.fulfillment.config.TestSecurityConfig;
import com.ahmedali.fulfillops.fulfillment.config.TestcontainersConfiguration;
import com.ahmedali.fulfillops.fulfillment.domain.Fulfillment;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentRepository;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentStatus;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentStatusHistory;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentStatusHistoryRepository;
import com.ahmedali.fulfillops.fulfillment.messaging.EventEnvelope;
import com.ahmedali.fulfillops.fulfillment.messaging.OrderCancellationRequestedListener;
import com.ahmedali.fulfillops.fulfillment.service.FulfillmentCommandService;
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
 * Covers OrderCancellationRequestedListener: cancels a still-cancellable fulfillment, and is a safe
 * no-op when there's no fulfillment for the order yet, it's already cancelled, or it can no longer
 * be cancelled (DISPATCHED) — Order Service's reconciliation is the safety net for that last case,
 * not an error surfaced here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class CancellationCompensationIT {

  @Autowired private OrderCancellationRequestedListener listener;
  @Autowired private FulfillmentRepository fulfillmentRepository;
  @Autowired private FulfillmentStatusHistoryRepository statusHistoryRepository;
  @Autowired private FulfillmentCommandService fulfillmentCommandService;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void cancelsAStillCancellableFulfillment() {
    Fulfillment fulfillment = saveNewFulfillment();

    listener.onMessage(
        envelope(
            fulfillment.getOrderId(),
            Map.of("reasonCode", "CUSTOMER_REQUESTED", "reasonDetail", "changed their mind")));

    Fulfillment updated =
        fulfillmentRepository.findById(fulfillment.getFulfillmentId()).orElseThrow();
    assertThat(updated.getStatus()).isEqualTo(FulfillmentStatus.CANCELLED);
    assertThat(updated.getCancellationReasonCode()).isEqualTo("ORDER_CANCELLATION_REQUESTED");
  }

  @Test
  void noFulfillmentYetIsANoOp() {
    listener.onMessage(
        envelope(
            UUID.randomUUID(), Map.of("reasonCode", "CUSTOMER_REQUESTED", "reasonDetail", "n/a")));
    // No exception — nothing exists for this order yet.
  }

  @Test
  void alreadyCancelledIsANoOp() {
    Fulfillment fulfillment = saveNewFulfillment();
    fulfillmentCommandService.cancel(
        fulfillment.getFulfillmentId(), 0, "already cancelled", "operator-1", UUID.randomUUID());

    listener.onMessage(
        envelope(
            fulfillment.getOrderId(),
            Map.of("reasonCode", "CUSTOMER_REQUESTED", "reasonDetail", "again")));

    long cancelledHistoryRows =
        statusHistoryRepository
            .findByFulfillmentIdOrderByOccurredAtAsc(fulfillment.getFulfillmentId())
            .stream()
            .filter(row -> row.getStatus() == FulfillmentStatus.CANCELLED)
            .count();
    assertThat(cancelledHistoryRows).isEqualTo(1);
  }

  @Test
  void dispatchedFulfillmentIsNotCancelled() {
    Fulfillment fulfillment = saveNewFulfillment();
    fulfillmentCommandService.advance(
        fulfillment.getFulfillmentId(),
        0,
        "PICKING",
        null,
        null,
        null,
        "operator-1",
        UUID.randomUUID());
    fulfillmentCommandService.advance(
        fulfillment.getFulfillmentId(),
        1,
        "PACKED",
        null,
        null,
        null,
        "operator-1",
        UUID.randomUUID());
    fulfillmentCommandService.advance(
        fulfillment.getFulfillmentId(),
        2,
        "DISPATCHED",
        "TRACK-1",
        null,
        null,
        "operator-1",
        UUID.randomUUID());

    listener.onMessage(
        envelope(
            fulfillment.getOrderId(),
            Map.of("reasonCode", "CUSTOMER_REQUESTED", "reasonDetail", "too late")));

    Fulfillment stillDispatched =
        fulfillmentRepository.findById(fulfillment.getFulfillmentId()).orElseThrow();
    assertThat(stillDispatched.getStatus()).isEqualTo(FulfillmentStatus.DISPATCHED);
  }

  private Fulfillment saveNewFulfillment() {
    Fulfillment fulfillment =
        Fulfillment.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "WH-ATL-1",
            Instant.now().plusSeconds(3600),
            UUID.randomUUID());
    fulfillmentRepository.save(fulfillment);
    statusHistoryRepository.save(
        new FulfillmentStatusHistory(
            fulfillment.getFulfillmentId(), FulfillmentStatus.ASSIGNED, "system", null));
    return fulfillment;
  }

  private String envelope(UUID orderId, Map<String, Object> payload) {
    EventEnvelope envelope =
        new EventEnvelope(
            UUID.randomUUID(),
            "OrderCancellationRequested",
            1,
            Instant.now(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            orderId,
            "order-service",
            objectMapper.valueToTree(payload));
    return objectMapper.writeValueAsString(envelope);
  }
}
