package com.ahmedali.fulfillops.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ahmedali.fulfillops.fulfillment.config.TestSecurityConfig;
import com.ahmedali.fulfillops.fulfillment.config.TestcontainersConfiguration;
import com.ahmedali.fulfillops.fulfillment.domain.Fulfillment;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentRepository;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentStatus;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentStatusHistory;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentStatusHistoryRepository;
import com.ahmedali.fulfillops.fulfillment.messaging.EventEnvelope;
import com.ahmedali.fulfillops.fulfillment.messaging.OutboxEvent;
import com.ahmedali.fulfillops.fulfillment.messaging.OutboxEventRepository;
import com.ahmedali.fulfillops.fulfillment.service.FulfillmentCommandService;
import com.ahmedali.fulfillops.fulfillment.service.InvalidFulfillmentRequestException;
import com.ahmedali.fulfillops.fulfillment.service.InvalidFulfillmentTransitionException;
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
 * Drives FulfillmentCommandService directly against a full application context backed by
 * Testcontainers Postgres/Kafka/Redis: the full advance chain, an invalid (skip-ahead) transition,
 * a stale If-Match version, the dispatch/delivery required-field rules, and the resulting audit
 * history and FulfillmentStatusChanged.v1 events.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class FulfillmentWorkflowIT {

  private static final Path EVENTS_DIR = Path.of("../../contracts/events");

  @Autowired private FulfillmentRepository fulfillmentRepository;
  @Autowired private FulfillmentStatusHistoryRepository statusHistoryRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private FulfillmentCommandService fulfillmentCommandService;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void advancingThroughEveryStatusRecordsHistoryAndPublishesSchemaValidEvents() throws Exception {
    Fulfillment fulfillment = saveNewFulfillment();
    UUID fulfillmentId = fulfillment.getFulfillmentId();
    UUID correlationId = UUID.randomUUID();

    advance(fulfillmentId, 0, "PICKING", null, null, correlationId);
    advance(fulfillmentId, 1, "PACKED", null, null, correlationId);
    advance(fulfillmentId, 2, "DISPATCHED", "TRACK-FICTIONAL-001", null, correlationId);
    advance(fulfillmentId, 3, "DELIVERED", null, Instant.now(), correlationId);

    Fulfillment updated = fulfillmentRepository.findById(fulfillmentId).orElseThrow();
    assertThat(updated.getStatus()).isEqualTo(FulfillmentStatus.DELIVERED);
    assertThat(updated.getTrackingReference()).isEqualTo("TRACK-FICTIONAL-001");
    assertThat(updated.getDeliveredAt()).isNotNull();

    List<FulfillmentStatusHistory> history =
        statusHistoryRepository.findByFulfillmentIdOrderByOccurredAtAsc(fulfillmentId);
    assertThat(history)
        .extracting(FulfillmentStatusHistory::getStatus)
        .containsExactly(
            FulfillmentStatus.ASSIGNED,
            FulfillmentStatus.PICKING,
            FulfillmentStatus.PACKED,
            FulfillmentStatus.DISPATCHED,
            FulfillmentStatus.DELIVERED);

    List<OutboxEvent> statusChangedEvents =
        outboxEventRepository.findAll().stream()
            .filter(
                row ->
                    row.getAggregateId().equals(fulfillment.getOrderId())
                        && row.getEventType().equals("FulfillmentStatusChanged"))
            .toList();
    assertThat(statusChangedEvents).hasSize(4);
    for (OutboxEvent event : statusChangedEvents) {
      assertThat(schemaErrorsFor(event, "FulfillmentStatusChanged")).isEmpty();
    }
  }

  @Test
  void skippingAheadToADispatchedStateFromAssignedIsRejected() {
    Fulfillment fulfillment = saveNewFulfillment();

    assertThatThrownBy(
            () ->
                fulfillmentCommandService.advance(
                    fulfillment.getFulfillmentId(),
                    0,
                    "DISPATCHED",
                    "TRACK-1",
                    null,
                    null,
                    "operator-1",
                    UUID.randomUUID()))
        .isInstanceOf(InvalidFulfillmentTransitionException.class);
  }

  @Test
  void aStaleIfMatchVersionIsRejected() {
    Fulfillment fulfillment = saveNewFulfillment();
    advance(fulfillment.getFulfillmentId(), 0, "PICKING", null, null, UUID.randomUUID());

    assertThatThrownBy(
            () ->
                advance(fulfillment.getFulfillmentId(), 0, "PACKED", null, null, UUID.randomUUID()))
        .hasMessageContaining("If-Match")
        .hasMessageContaining("refresh")
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void dispatchingWithoutATrackingReferenceIsRejected() {
    Fulfillment fulfillment = saveNewFulfillment();
    advance(fulfillment.getFulfillmentId(), 0, "PICKING", null, null, UUID.randomUUID());
    advance(fulfillment.getFulfillmentId(), 1, "PACKED", null, null, UUID.randomUUID());

    assertThatThrownBy(
            () ->
                fulfillmentCommandService.advance(
                    fulfillment.getFulfillmentId(),
                    2,
                    "DISPATCHED",
                    null,
                    null,
                    null,
                    "operator-1",
                    UUID.randomUUID()))
        .isInstanceOf(InvalidFulfillmentRequestException.class);
  }

  @Test
  void deliveringWithoutADeliveredAtIsRejected() {
    Fulfillment fulfillment = saveNewFulfillment();
    advance(fulfillment.getFulfillmentId(), 0, "PICKING", null, null, UUID.randomUUID());
    advance(fulfillment.getFulfillmentId(), 1, "PACKED", null, null, UUID.randomUUID());
    advance(fulfillment.getFulfillmentId(), 2, "DISPATCHED", "TRACK-2", null, UUID.randomUUID());

    assertThatThrownBy(
            () ->
                fulfillmentCommandService.advance(
                    fulfillment.getFulfillmentId(),
                    3,
                    "DELIVERED",
                    null,
                    null,
                    null,
                    "operator-1",
                    UUID.randomUUID()))
        .isInstanceOf(InvalidFulfillmentRequestException.class);
  }

  private void advance(
      UUID fulfillmentId,
      long expectedVersion,
      String newStatus,
      String trackingReference,
      Instant deliveredAt,
      UUID correlationId) {
    fulfillmentCommandService.advance(
        fulfillmentId,
        expectedVersion,
        newStatus,
        trackingReference,
        deliveredAt,
        null,
        "operator-1",
        correlationId);
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
