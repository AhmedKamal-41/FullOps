package com.ahmedali.fulfillops.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import com.ahmedali.fulfillops.fulfillment.config.TestSecurityConfig;
import com.ahmedali.fulfillops.fulfillment.config.TestcontainersConfiguration;
import com.ahmedali.fulfillops.fulfillment.domain.Fulfillment;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentRepository;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentStatus;
import com.ahmedali.fulfillops.fulfillment.messaging.EventEnvelope;
import com.ahmedali.fulfillops.fulfillment.messaging.OutboxEvent;
import com.ahmedali.fulfillops.fulfillment.messaging.OutboxEventRepository;
import com.ahmedali.fulfillops.fulfillment.messaging.PaymentAuthorizedListener;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
 * Drives PaymentAuthorizedListener directly against a full application context backed by
 * Testcontainers Postgres/Kafka/Redis: a paid order creates exactly one fulfillment plus a
 * schema-valid FulfillmentAssigned.v1 event, and a duplicate delivery of the same event is a no-op.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class FulfillmentAssignmentIT {

  private static final Path EVENTS_DIR = Path.of("../../contracts/events");

  @Autowired private PaymentAuthorizedListener paymentAuthorizedListener;
  @Autowired private FulfillmentRepository fulfillmentRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void aPaidOrderCreatesExactlyOneFulfillmentAssignedToAWarehouseWithAnSlaDueDate()
      throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();

    deliverPaymentAuthorized(UUID.randomUUID(), orderId, correlationId);

    Fulfillment fulfillment = fulfillmentRepository.findByOrderId(orderId).orElseThrow();
    assertThat(fulfillment.getStatus()).isEqualTo(FulfillmentStatus.ASSIGNED);
    assertThat(fulfillment.getWarehouseId()).isNotBlank();
    assertThat(fulfillment.getSlaDueAt()).isAfter(Instant.now());
    assertThat(fulfillment.getAssigneeId()).isNull();

    OutboxEvent assignedEvent = outboxEventFor(orderId, "FulfillmentAssigned");
    assertThat(schemaErrorsFor(assignedEvent, "FulfillmentAssigned")).isEmpty();
  }

  @Test
  void aSecondDeliveryOfTheSameEventIsANoOp() {
    UUID orderId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    String envelopeJson = paymentAuthorizedEnvelopeJson(eventId, orderId, correlationId);

    paymentAuthorizedListener.onMessage(envelopeJson);
    paymentAuthorizedListener.onMessage(envelopeJson);

    long fulfillmentCount =
        fulfillmentRepository.findAll().stream()
            .filter(fulfillment -> fulfillment.getOrderId().equals(orderId))
            .count();
    assertThat(fulfillmentCount).isEqualTo(1);

    long assignedEventCount =
        outboxEventRepository.findAll().stream()
            .filter(
                row ->
                    row.getAggregateId().equals(orderId)
                        && row.getEventType().equals("FulfillmentAssigned"))
            .count();
    assertThat(assignedEventCount).isEqualTo(1);
  }

  private void deliverPaymentAuthorized(UUID eventId, UUID orderId, UUID correlationId) {
    paymentAuthorizedListener.onMessage(
        paymentAuthorizedEnvelopeJson(eventId, orderId, correlationId));
  }

  private String paymentAuthorizedEnvelopeJson(UUID eventId, UUID orderId, UUID correlationId) {
    Map<String, Object> payload =
        Map.of(
            "paymentId", UUID.randomUUID().toString(),
            "amount", Map.of("currencyCode", "USD", "amount", "50.00"));
    EventEnvelope envelope =
        new EventEnvelope(
            eventId,
            "PaymentAuthorized",
            1,
            Instant.now(),
            correlationId,
            UUID.randomUUID(),
            orderId,
            "payment-service",
            objectMapper.valueToTree(payload));
    return objectMapper.writeValueAsString(envelope);
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
