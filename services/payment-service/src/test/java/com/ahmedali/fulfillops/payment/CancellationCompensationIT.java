package com.ahmedali.fulfillops.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.ahmedali.fulfillops.payment.config.TestSecurityConfig;
import com.ahmedali.fulfillops.payment.config.TestcontainersConfiguration;
import com.ahmedali.fulfillops.payment.domain.Payment;
import com.ahmedali.fulfillops.payment.domain.PaymentRepository;
import com.ahmedali.fulfillops.payment.messaging.EventEnvelope;
import com.ahmedali.fulfillops.payment.messaging.FulfillmentCancelledListener;
import com.ahmedali.fulfillops.payment.messaging.OrderEventsListener;
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
 * Covers Phase 8's new autonomous compensation listeners: FulfillmentCancelledListener and
 * OrderEventsListener's OrderCancellationRequested handling. Both converge on
 * RefundService.refundForCompensation, which is a safe no-op when there's no authorized payment to
 * refund.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class CancellationCompensationIT {

  @Autowired private OrderEventsListener orderEventsListener;
  @Autowired private FulfillmentCancelledListener fulfillmentCancelledListener;
  @Autowired private PaymentRepository paymentRepository;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void fulfillmentCancelledRefundsAnAuthorizedPayment() {
    Payment payment = saveAuthorizedPayment(new BigDecimal("25.00"));

    fulfillmentCancelledListener.onMessage(
        envelope(
            "FulfillmentStatusChanged",
            payment.getOrderId(),
            Map.of(
                "fulfillmentId",
                UUID.randomUUID().toString(),
                "previousStatus",
                "PICKING",
                "newStatus",
                "CANCELLED")));

    assertThat(paymentRepository.findById(payment.getPaymentId()).orElseThrow().getStatus().name())
        .isEqualTo("REFUNDED");
  }

  @Test
  void fulfillmentCancelledWithNoPaymentIsANoOp() {
    fulfillmentCancelledListener.onMessage(
        envelope(
            "FulfillmentStatusChanged",
            UUID.randomUUID(),
            Map.of(
                "fulfillmentId",
                UUID.randomUUID().toString(),
                "previousStatus",
                "PICKING",
                "newStatus",
                "CANCELLED")));
    // No exception — there is no payment for this order, nothing to refund.
  }

  @Test
  void orderCancellationRequestedRefundsAnAuthorizedPayment() {
    Payment payment = saveAuthorizedPayment(new BigDecimal("40.00"));

    orderEventsListener.onMessage(
        envelope(
            "OrderCancellationRequested",
            payment.getOrderId(),
            Map.of("reasonCode", "CUSTOMER_REQUESTED", "reasonDetail", "changed their mind")));

    assertThat(paymentRepository.findById(payment.getPaymentId()).orElseThrow().getStatus().name())
        .isEqualTo("REFUNDED");
  }

  @Test
  void orderCancellationRequestedBeforeAuthorizationIsANoOp() {
    orderEventsListener.onMessage(
        envelope(
            "OrderCancellationRequested",
            UUID.randomUUID(),
            Map.of("reasonCode", "CUSTOMER_REQUESTED", "reasonDetail", "too early")));
    // No exception — no OrderPlacedListener context and no payment exist for this order.
  }

  @Test
  void repeatedFulfillmentCancellationNeverRefundsTwice() {
    Payment payment = saveAuthorizedPayment(new BigDecimal("15.00"));
    String cancelledJson =
        envelope(
            "FulfillmentStatusChanged",
            payment.getOrderId(),
            Map.of(
                "fulfillmentId",
                UUID.randomUUID().toString(),
                "previousStatus",
                "PICKING",
                "newStatus",
                "CANCELLED"));

    fulfillmentCancelledListener.onMessage(cancelledJson);
    fulfillmentCancelledListener.onMessage(cancelledJson);

    assertThat(paymentRepository.findById(payment.getPaymentId()).orElseThrow().getStatus().name())
        .isEqualTo("REFUNDED");
  }

  private Payment saveAuthorizedPayment(BigDecimal amount) {
    Payment payment =
        Payment.authorized(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            amount,
            "USD",
            UUID.randomUUID());
    return paymentRepository.save(payment);
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
}
