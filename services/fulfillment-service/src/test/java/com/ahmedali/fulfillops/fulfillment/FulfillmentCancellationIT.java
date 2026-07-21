package com.ahmedali.fulfillops.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

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
import com.ahmedali.fulfillops.fulfillment.service.FulfillmentCancellationNotAllowedException;
import com.ahmedali.fulfillops.fulfillment.service.FulfillmentCommandService;
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
 * Covers the Phase 7 cancellation rules: allowed before DISPATCHED (with a schema-valid
 * FulfillmentStatusChanged.v1 carrying reasonCode=OPERATOR_CANCELLED), rejected with a
 * machine-readable reason once DISPATCHED, and idempotent (a no-op, not an error) when repeated.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class FulfillmentCancellationIT {

  private static final Path EVENTS_DIR = Path.of("../../contracts/events");

  @Autowired private FulfillmentRepository fulfillmentRepository;
  @Autowired private FulfillmentStatusHistoryRepository statusHistoryRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private FulfillmentCommandService fulfillmentCommandService;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void cancellingBeforeDispatchMarksCancelledAndPublishesASchemaValidEvent() throws Exception {
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

    fulfillmentCommandService.cancel(
        fulfillment.getFulfillmentId(),
        1,
        "customer requested cancellation",
        "operator-1",
        UUID.randomUUID());

    Fulfillment cancelled =
        fulfillmentRepository.findById(fulfillment.getFulfillmentId()).orElseThrow();
    assertThat(cancelled.getStatus()).isEqualTo(FulfillmentStatus.CANCELLED);
    assertThat(cancelled.getCancellationReasonCode()).isEqualTo("OPERATOR_CANCELLED");
    assertThat(cancelled.getCancellationReasonDetail())
        .isEqualTo("customer requested cancellation");

    List<FulfillmentStatusHistory> history =
        statusHistoryRepository.findByFulfillmentIdOrderByOccurredAtAsc(
            fulfillment.getFulfillmentId());
    assertThat(history)
        .extracting(FulfillmentStatusHistory::getStatus)
        .containsExactly(
            FulfillmentStatus.ASSIGNED, FulfillmentStatus.PICKING, FulfillmentStatus.CANCELLED);

    OutboxEvent cancelledEvent = outboxEventFor(fulfillment.getOrderId());
    assertThat(schemaErrorsFor(cancelledEvent)).isEmpty();
  }

  @Test
  void cancellingAfterDispatchIsRejectedWithAMachineReadableReasonCode() {
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

    FulfillmentCancellationNotAllowedException rejection =
        catchThrowableOfType(
            () ->
                fulfillmentCommandService.cancel(
                    fulfillment.getFulfillmentId(),
                    3,
                    "too late now",
                    "operator-1",
                    UUID.randomUUID()),
            FulfillmentCancellationNotAllowedException.class);
    assertThat(rejection.getReasonCode()).isEqualTo("CANCELLATION_NOT_ALLOWED_AFTER_DISPATCH");

    Fulfillment stillDispatched =
        fulfillmentRepository.findById(fulfillment.getFulfillmentId()).orElseThrow();
    assertThat(stillDispatched.getStatus()).isEqualTo(FulfillmentStatus.DISPATCHED);
  }

  @Test
  void repeatingACancellationIsANoOp() {
    Fulfillment fulfillment = saveNewFulfillment();

    Fulfillment firstCancel =
        fulfillmentCommandService.cancel(
            fulfillment.getFulfillmentId(), 0, "first reason", "operator-1", UUID.randomUUID());
    Fulfillment secondCancel =
        fulfillmentCommandService.cancel(
            fulfillment.getFulfillmentId(), 0, "second reason", "operator-2", UUID.randomUUID());

    assertThat(secondCancel.getCancellationReasonDetail())
        .isEqualTo(firstCancel.getCancellationReasonDetail());

    long cancelledHistoryRows =
        statusHistoryRepository
            .findByFulfillmentIdOrderByOccurredAtAsc(fulfillment.getFulfillmentId())
            .stream()
            .filter(row -> row.getStatus() == FulfillmentStatus.CANCELLED)
            .count();
    assertThat(cancelledHistoryRows).isEqualTo(1);

    long cancelledEventCount =
        outboxEventRepository.findAll().stream()
            .filter(
                row ->
                    row.getAggregateId().equals(fulfillment.getOrderId())
                        && row.getEventType().equals("FulfillmentStatusChanged"))
            .count();
    assertThat(cancelledEventCount).isEqualTo(1);
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

  private OutboxEvent outboxEventFor(UUID orderId) {
    return outboxEventRepository.findAll().stream()
        .filter(
            row ->
                row.getAggregateId().equals(orderId)
                    && row.getEventType().equals("FulfillmentStatusChanged"))
        .findFirst()
        .orElseThrow();
  }

  private List<Error> schemaErrorsFor(OutboxEvent outboxRow) throws IOException {
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
    return schemaFor("FulfillmentStatusChanged").validate(objectMapper.readTree(envelopeJson));
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
