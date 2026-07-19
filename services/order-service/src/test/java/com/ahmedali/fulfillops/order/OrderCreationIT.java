package com.ahmedali.fulfillops.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ahmedali.fulfillops.order.config.TestSecurityConfig;
import com.ahmedali.fulfillops.order.config.TestcontainersConfiguration;
import com.ahmedali.fulfillops.order.messaging.EventEnvelope;
import com.ahmedali.fulfillops.order.messaging.OutboxEvent;
import com.ahmedali.fulfillops.order.messaging.OutboxEventRepository;
import com.ahmedali.fulfillops.order.web.dto.CreateOrderItemRequest;
import com.ahmedali.fulfillops.order.web.dto.CreateOrderRequest;
import com.ahmedali.fulfillops.order.web.dto.MoneyDto;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives POST /api/v1/orders through real MockMvc (jwt() injects a test-issued Authentication the
 * same way WhoAmIAuthorizationTest does — see that class for why this doesn't need a running
 * Keycloak) against a full application context backed by Testcontainers Postgres, and asserts
 * directly against the database what the transaction actually committed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class OrderCreationIT {

  private static final Path EVENTS_DIR = Path.of("../../contracts/events");

  @Autowired private MockMvc mockMvc;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private OutboxEventRepository outboxEventRepository;

  @Autowired private ObjectMapper objectMapper;

  @Test
  void placingAnOrderPersistsTheOrderItemsHistoryIdempotencyRowAndOutboxEventTogether()
      throws Exception {
    UUID customerId = UUID.randomUUID();
    String idempotencyKey = "checkout-" + UUID.randomUUID();

    String responseJson =
        mockMvc
            .perform(
                post("/api/v1/orders")
                    .with(customer(customerId))
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType("application/json")
                    .content(objectMapper.writeValueAsString(sampleRequest())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.totalAmount.amount").value("64.48"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    UUID orderId = UUID.fromString(objectMapper.readTree(responseJson).get("orderId").asString());

    assertThat(countRows("orders", "order_id = ?", orderId)).isEqualTo(1);
    assertThat(countRows("order_items", "order_id = ?", orderId)).isEqualTo(2);
    assertThat(countRows("order_status_history", "order_id = ? AND status = 'PENDING'", orderId))
        .isEqualTo(1);
    assertThat(countRows("idempotency_requests", "order_id = ?", orderId)).isEqualTo(1);
    assertThat(
            countRows("outbox_event", "aggregate_id = ? AND event_type = 'OrderPlaced'", orderId))
        .isEqualTo(1);
  }

  @Test
  void replayingTheSameKeyAndIdenticalPayloadReturnsTheOriginalOrder() throws Exception {
    UUID customerId = UUID.randomUUID();
    String idempotencyKey = "checkout-" + UUID.randomUUID();
    CreateOrderRequest request = sampleRequest();

    UUID firstOrderId = placeOrder(customerId, idempotencyKey, request);
    UUID secondOrderId = placeOrder(customerId, idempotencyKey, request);

    assertThat(secondOrderId).isEqualTo(firstOrderId);
    assertThat(countRows("orders", "customer_id = ?", customerId)).isEqualTo(1);
  }

  @Test
  void reusingTheKeyWithADifferentPayloadIsRejectedWithConflict() throws Exception {
    UUID customerId = UUID.randomUUID();
    String idempotencyKey = "checkout-" + UUID.randomUUID();
    placeOrder(customerId, idempotencyKey, sampleRequest());

    CreateOrderRequest differentRequest =
        new CreateOrderRequest(
            List.of(new CreateOrderItemRequest("SOMETHING-ELSE", 9, new MoneyDto("USD", "1.00"))));

    mockMvc
        .perform(
            post("/api/v1/orders")
                .with(customer(customerId))
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(differentRequest)))
        .andExpect(status().isConflict());

    assertThat(countRows("orders", "customer_id = ?", customerId)).isEqualTo(1);
  }

  @Test
  void theOrderPlacedOutboxEventValidatesAgainstItsJsonSchema() throws Exception {
    UUID customerId = UUID.randomUUID();
    String idempotencyKey = "checkout-" + UUID.randomUUID();
    UUID orderId = placeOrder(customerId, idempotencyKey, sampleRequest());

    OutboxEvent outboxRow =
        outboxEventRepository.findAll().stream()
            .filter(row -> row.getAggregateId().equals(orderId))
            .findFirst()
            .orElseThrow();

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

    List<Error> errors = orderPlacedSchema().validate(objectMapper.readTree(envelopeJson));

    assertThat(errors)
        .as(
            "schema validation errors: %s",
            errors.stream().map(Error::getMessage).collect(Collectors.joining("; ")))
        .isEmpty();
  }

  private UUID placeOrder(UUID customerId, String idempotencyKey, CreateOrderRequest request)
      throws Exception {
    String response =
        mockMvc
            .perform(
                post("/api/v1/orders")
                    .with(customer(customerId))
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType("application/json")
                    .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(objectMapper.readTree(response).get("orderId").asString());
  }

  private static RequestPostProcessor customer(UUID customerId) {
    return SecurityMockMvcRequestPostProcessors.jwt()
        .jwt(token -> token.subject(customerId.toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
  }

  private CreateOrderRequest sampleRequest() {
    return new CreateOrderRequest(
        List.of(
            new CreateOrderItemRequest("WIDGET-BLUE-M", 2, new MoneyDto("USD", "19.99")),
            new CreateOrderItemRequest("WIDGET-RED-L", 1, new MoneyDto("USD", "24.50"))));
  }

  private int countRows(String table, String where, Object param) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE " + where, Integer.class, param);
    return count == null ? 0 : count;
  }

  private Schema orderPlacedSchema() throws IOException {
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
        SchemaLocation.of("https://fulfillops.dev/contracts/events/OrderPlaced.v1.schema.json"));
  }
}
