package com.ahmedali.fulfillops.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ahmedali.fulfillops.payment.config.TestSecurityConfig;
import com.ahmedali.fulfillops.payment.config.TestcontainersConfiguration;
import com.ahmedali.fulfillops.payment.domain.Payment;
import com.ahmedali.fulfillops.payment.domain.PaymentRepository;
import com.ahmedali.fulfillops.payment.domain.Refund;
import com.ahmedali.fulfillops.payment.domain.RefundReasonCode;
import com.ahmedali.fulfillops.payment.domain.RefundRepository;
import com.ahmedali.fulfillops.payment.messaging.EventEnvelope;
import com.ahmedali.fulfillops.payment.messaging.OutboxEvent;
import com.ahmedali.fulfillops.payment.messaging.OutboxEventRepository;
import com.ahmedali.fulfillops.payment.service.IdempotencyKeyConflictException;
import com.ahmedali.fulfillops.payment.service.InvalidRefundStateException;
import com.ahmedali.fulfillops.payment.service.RefundService;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives RefundService directly against a full application context backed by Testcontainers
 * Postgres/Kafka/Redis. Covers "refund once," "duplicate refund is a safe replay," "invalid refund
 * state is rejected," and "a reused Idempotency-Key with a different payload is a conflict" from
 * the refund contract, plus PaymentRefunded.v1 schema conformance.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class RefundIT {

  private static final Path EVENTS_DIR = Path.of("../../contracts/events");

  @Autowired private RefundService refundService;
  @Autowired private PaymentRepository paymentRepository;
  @Autowired private RefundRepository refundRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void refundingAnAuthorizedPaymentMarksItRefundedAndPublishesPaymentRefunded() throws Exception {
    Payment payment = saveAuthorizedPayment(new BigDecimal("25.00"));

    Refund refund =
        refundService.refund(
            "operator-1",
            "refund-key-1",
            payment.getPaymentId(),
            RefundReasonCode.FULFILLMENT_CANCELLED,
            UUID.randomUUID());

    assertThat(refund.getAmount()).isEqualByComparingTo("25.00");
    assertThat(paymentRepository.findById(payment.getPaymentId()).orElseThrow().getStatus().name())
        .isEqualTo("REFUNDED");

    OutboxEvent refundedEvent = outboxEventFor(payment.getOrderId(), "PaymentRefunded");
    assertThat(schemaErrorsFor(refundedEvent, "PaymentRefunded")).isEmpty();
  }

  @Test
  void repeatingTheSameKeyAndPayloadReturnsTheOriginalRefundWithoutCreatingASecondOne() {
    Payment payment = saveAuthorizedPayment(new BigDecimal("25.00"));

    Refund first =
        refundService.refund(
            "operator-2",
            "refund-key-2",
            payment.getPaymentId(),
            RefundReasonCode.FULFILLMENT_CANCELLED,
            UUID.randomUUID());
    Refund second =
        refundService.refund(
            "operator-2",
            "refund-key-2",
            payment.getPaymentId(),
            RefundReasonCode.FULFILLMENT_CANCELLED,
            UUID.randomUUID());

    assertThat(second.getRefundId()).isEqualTo(first.getRefundId());
    assertThat(refundRepository.findByPaymentId(payment.getPaymentId())).isPresent();
  }

  @Test
  void reusingTheKeyWithADifferentPaymentIsRejectedAsAConflict() {
    Payment first = saveAuthorizedPayment(new BigDecimal("25.00"));
    Payment second = saveAuthorizedPayment(new BigDecimal("30.00"));
    refundService.refund(
        "operator-3",
        "refund-key-3",
        first.getPaymentId(),
        RefundReasonCode.FULFILLMENT_CANCELLED,
        UUID.randomUUID());

    assertThatThrownBy(
            () ->
                refundService.refund(
                    "operator-3",
                    "refund-key-3",
                    second.getPaymentId(),
                    RefundReasonCode.FULFILLMENT_CANCELLED,
                    UUID.randomUUID()))
        .isInstanceOf(IdempotencyKeyConflictException.class);
  }

  @Test
  void refundingAnAlreadyDeclinedPaymentIsRejected() {
    Payment declined = saveDeclinedPayment(new BigDecimal("25.00"));

    assertThatThrownBy(
            () ->
                refundService.refund(
                    "operator-4",
                    "refund-key-4",
                    declined.getPaymentId(),
                    RefundReasonCode.FULFILLMENT_CANCELLED,
                    UUID.randomUUID()))
        .isInstanceOf(InvalidRefundStateException.class);
  }

  @Test
  void refundingAnAlreadyRefundedPaymentWithADifferentActorAndKeyIsRejected() {
    Payment payment = saveAuthorizedPayment(new BigDecimal("25.00"));
    refundService.refund(
        "operator-5a",
        "refund-key-5a",
        payment.getPaymentId(),
        RefundReasonCode.FULFILLMENT_CANCELLED,
        UUID.randomUUID());

    assertThatThrownBy(
            () ->
                refundService.refund(
                    "operator-5b",
                    "refund-key-5b",
                    payment.getPaymentId(),
                    RefundReasonCode.FULFILLMENT_CANCELLED,
                    UUID.randomUUID()))
        .isInstanceOf(InvalidRefundStateException.class);
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

  private Payment saveDeclinedPayment(BigDecimal amount) {
    Payment payment =
        Payment.declined(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            amount,
            "USD",
            "SIMULATED_CARD_DECLINED",
            "no soup for you",
            UUID.randomUUID());
    return paymentRepository.save(payment);
  }

  private OutboxEvent outboxEventFor(UUID orderId, String eventType) {
    return outboxEventRepository.findAll().stream()
        .filter(row -> row.getAggregateId().equals(orderId) && row.getEventType().equals(eventType))
        .findFirst()
        .orElseThrow();
  }

  private List<Error> schemaErrorsFor(OutboxEvent outboxRow, String schemaName) throws IOException {
    EventEnvelope envelope =
        new EventEnvelope(
            outboxRow.getEventId(),
            outboxRow.getEventType(),
            outboxRow.getEventVersion(),
            outboxRow.getOccurredAt(),
            outboxRow.getCorrelationId(),
            outboxRow.getCausationId(),
            outboxRow.getAggregateId(),
            outboxRow.getProducer(),
            objectMapper.readTree(outboxRow.getPayload()));
    String envelopeJson = objectMapper.writeValueAsString(envelope);
    return schemaFor(schemaName).validate(objectMapper.readTree(envelopeJson));
  }

  private Schema schemaFor(String schemaName) throws IOException {
    Map<String, String> contentById = new HashMap<>();
    try (var files = Files.list(EVENTS_DIR)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".schema.json")).toList()) {
        String content = Files.readString(file);
        String id = objectMapper.readTree(content).get("$id").asString();
        contentById.put(id, content);
      }
    }
    SchemaRegistry registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12, builder -> builder.schemas(contentById));
    return registry.getSchema(
        SchemaLocation.of(
            "https://fulfillops.dev/contracts/events/" + schemaName + ".v1.schema.json"));
  }
}
