package com.ahmedali.fulfillops.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.ahmedali.fulfillops.inventory.config.TestSecurityConfig;
import com.ahmedali.fulfillops.inventory.config.TestcontainersConfiguration;
import com.ahmedali.fulfillops.inventory.domain.InventoryAdjustment;
import com.ahmedali.fulfillops.inventory.domain.InventoryAdjustmentRepository;
import com.ahmedali.fulfillops.inventory.domain.Product;
import com.ahmedali.fulfillops.inventory.domain.ProductRepository;
import com.ahmedali.fulfillops.inventory.domain.StockLevel;
import com.ahmedali.fulfillops.inventory.domain.StockLevelRepository;
import com.ahmedali.fulfillops.inventory.messaging.EventEnvelope;
import com.ahmedali.fulfillops.inventory.messaging.OrderEventsListener;
import com.ahmedali.fulfillops.inventory.messaging.OutboxEvent;
import com.ahmedali.fulfillops.inventory.messaging.OutboxEventRepository;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Drives OrderEventsListener.onMessage(...) directly against a full application context backed by
 * Testcontainers Postgres/Kafka/Redis, and asserts directly against the database what the
 * reservation transaction actually committed. Covers the "full reservation", "insufficient one
 * item", "atomic multi-item rejection", and "duplicate event" scenarios.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class ReservationIT {

  private static final Path EVENTS_DIR = Path.of("../../contracts/events");

  @Autowired private OrderEventsListener orderEventsListener;
  @Autowired private ProductRepository productRepository;
  @Autowired private StockLevelRepository stockLevelRepository;
  @Autowired private InventoryAdjustmentRepository adjustmentRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void fullMultiItemReservationDecrementsStockForEveryItemAndWritesOneReservedEvent()
      throws Exception {
    String skuA = uniqueSku("BLUE");
    String skuB = uniqueSku("RED");
    seedStock(skuA, 10);
    seedStock(skuB, 5);
    UUID orderId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();

    orderEventsListener.onMessage(
        orderPlacedEnvelopeJson(
            UUID.randomUUID(),
            orderId,
            correlationId,
            List.of(new TestItem(skuA, 2), new TestItem(skuB, 3))));

    assertThat(stockLevelRepository.findBySku(skuA).orElseThrow().getAvailableQuantity())
        .isEqualTo(8);
    assertThat(stockLevelRepository.findBySku(skuA).orElseThrow().getReservedQuantity())
        .isEqualTo(2);
    assertThat(stockLevelRepository.findBySku(skuB).orElseThrow().getAvailableQuantity())
        .isEqualTo(2);
    assertThat(stockLevelRepository.findBySku(skuB).orElseThrow().getReservedQuantity())
        .isEqualTo(3);

    assertThat(countRows("inventory_reservation", "order_id = ? AND status = 'RESERVED'", orderId))
        .isEqualTo(1);
    assertThat(countRowsBySku("reservation_item", skuA)).isEqualTo(1);
    assertThat(countRowsBySku("reservation_item", skuB)).isEqualTo(1);
    assertThat(
            countRows(
                "outbox_event", "aggregate_id = ? AND event_type = 'InventoryReserved'", orderId))
        .isEqualTo(1);

    OutboxEvent reservedEvent = outboxEventFor(orderId, "InventoryReserved");
    assertThat(schemaErrorsFor(reservedEvent, "InventoryReserved")).isEmpty();
  }

  @Test
  void insufficientStockRejectsTheOrderAndMutatesNothing() throws Exception {
    String sku = uniqueSku("SHORT");
    seedStock(sku, 1);
    UUID orderId = UUID.randomUUID();

    orderEventsListener.onMessage(
        orderPlacedEnvelopeJson(
            UUID.randomUUID(), orderId, UUID.randomUUID(), List.of(new TestItem(sku, 5))));

    assertThat(stockLevelRepository.findBySku(sku).orElseThrow().getAvailableQuantity())
        .isEqualTo(1);
    assertThat(countRows("inventory_reservation", "order_id = ?", orderId)).isZero();
    assertThat(
            countRows(
                "outbox_event", "aggregate_id = ? AND event_type = 'InventoryRejected'", orderId))
        .isEqualTo(1);

    OutboxEvent rejectedEvent = outboxEventFor(orderId, "InventoryRejected");
    assertThat(schemaErrorsFor(rejectedEvent, "InventoryRejected")).isEmpty();
  }

  @Test
  void oneInsufficientItemRejectsTheWholeOrderLeavingEvenTheSufficientItemUntouched()
      throws Exception {
    String sufficientSku = uniqueSku("PLENTY");
    String shortSku = uniqueSku("SCARCE");
    seedStock(sufficientSku, 100);
    seedStock(shortSku, 1);
    UUID orderId = UUID.randomUUID();

    orderEventsListener.onMessage(
        orderPlacedEnvelopeJson(
            UUID.randomUUID(),
            orderId,
            UUID.randomUUID(),
            List.of(new TestItem(sufficientSku, 2), new TestItem(shortSku, 2))));

    assertThat(stockLevelRepository.findBySku(sufficientSku).orElseThrow().getAvailableQuantity())
        .as("the item with plenty of stock must not be touched by an order that's rejected overall")
        .isEqualTo(100);
    assertThat(stockLevelRepository.findBySku(shortSku).orElseThrow().getAvailableQuantity())
        .isEqualTo(1);
    assertThat(countRows("inventory_reservation", "order_id = ?", orderId)).isZero();
    assertThat(countRowsBySku("reservation_item", sufficientSku)).isZero();
  }

  @Test
  void duplicateDeliveryOfTheSameEventIsANoOp() throws Exception {
    String sku = uniqueSku("DUP");
    seedStock(sku, 10);
    UUID eventId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    String envelopeJson =
        orderPlacedEnvelopeJson(eventId, orderId, UUID.randomUUID(), List.of(new TestItem(sku, 2)));

    orderEventsListener.onMessage(envelopeJson);
    orderEventsListener.onMessage(envelopeJson);

    assertThat(stockLevelRepository.findBySku(sku).orElseThrow().getAvailableQuantity())
        .isEqualTo(8);
    assertThat(countRows("inventory_reservation", "order_id = ?", orderId)).isEqualTo(1);
    assertThat(countRows("inbox_event", "aggregate_id = ?", orderId)).isEqualTo(1);
  }

  @Test
  void auditEntriesRecordActorReasonCorrelationAndBeforeAfterQuantities() throws Exception {
    String sku = uniqueSku("AUDIT");
    seedStock(sku, 10);
    UUID orderId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();

    orderEventsListener.onMessage(
        orderPlacedEnvelopeJson(
            UUID.randomUUID(), orderId, correlationId, List.of(new TestItem(sku, 4))));

    InventoryAdjustment adjustment =
        adjustmentRepository.findAll().stream()
            .filter(row -> row.getSku().equals(sku))
            .findFirst()
            .orElseThrow();
    assertThat(adjustment.getSource().name()).isEqualTo("RESERVATION");
    assertThat(adjustment.getChangeQuantity()).isEqualTo(-4);
    assertThat(adjustment.getQuantityBefore()).isEqualTo(10);
    assertThat(adjustment.getQuantityAfter()).isEqualTo(6);
    assertThat(adjustment.getActor()).isEqualTo("system");
    assertThat(adjustment.getCorrelationId()).isEqualTo(correlationId);
    assertThat(adjustment.getCreatedAt()).isNotNull();
  }

  @Test
  void reservingBelowTheLowStockThresholdEmitsASchemaValidInventoryLowStockEvent()
      throws Exception {
    // app.inventory.low-stock.default-threshold is 10 in application-test.yml — 12 available,
    // reserve 3, lands at 9, which crosses below 10.
    String sku = uniqueSku("LOWSTOCK");
    seedStock(sku, 12);
    UUID orderId = UUID.randomUUID();

    orderEventsListener.onMessage(
        orderPlacedEnvelopeJson(
            UUID.randomUUID(), orderId, UUID.randomUUID(), List.of(new TestItem(sku, 3))));

    UUID skuAggregateId = UUID.nameUUIDFromBytes(("sku:" + sku).getBytes(StandardCharsets.UTF_8));
    assertThat(
            countRows(
                "outbox_event",
                "aggregate_id = ? AND event_type = 'InventoryLowStock'",
                skuAggregateId))
        .isEqualTo(1);

    OutboxEvent lowStockEvent =
        outboxEventRepository.findAll().stream()
            .filter(
                row ->
                    row.getAggregateId().equals(skuAggregateId)
                        && row.getEventType().equals("InventoryLowStock"))
            .findFirst()
            .orElseThrow();
    assertThat(schemaErrorsFor(lowStockEvent, "InventoryLowStock")).isEmpty();
    assertThat(lowStockEvent.getPayload()).contains("\"belowThreshold\": true");
  }

  @Test
  void databaseConstraintsRejectNegativeAvailableQuantityEvenBypassingTheApplication() {
    String sku = uniqueSku("CONSTRAINT");
    seedStock(sku, 5);

    org.junit.jupiter.api.Assertions.assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            jdbcTemplate.update(
                "UPDATE stock_level SET available_quantity = -1 WHERE sku = ?", sku));
  }

  @Test
  void aRealOrderPlacedExampleFixtureIsAcceptedByTheListener() throws Exception {
    String fixtureJson =
        Files.readString(Path.of("../../contracts/events/examples/OrderPlaced.v1.example.json"));
    ObjectNode envelopeNode = (ObjectNode) objectMapper.readTree(fixtureJson);
    UUID orderId = UUID.randomUUID();
    String skuA = uniqueSku("FIXTURE-BLUE");
    String skuB = uniqueSku("FIXTURE-RED");
    seedStock(skuA, 10);
    seedStock(skuB, 10);

    // Patch only the identifiers and SKUs so this test's stock doesn't collide with any
    // other test's — everything else (field names, unitPrice/totalAmount shape, nullable
    // causationId) stays exactly as the real contract example defines it.
    envelopeNode.put("eventId", UUID.randomUUID().toString());
    envelopeNode.put("aggregateId", orderId.toString());
    envelopeNode.put("correlationId", UUID.randomUUID().toString());
    ArrayNode itemsNode = (ArrayNode) envelopeNode.get("payload").get("items");
    ((ObjectNode) itemsNode.get(0)).put("sku", skuA);
    ((ObjectNode) itemsNode.get(1)).put("sku", skuB);

    orderEventsListener.onMessage(objectMapper.writeValueAsString(envelopeNode));

    assertThat(countRows("inventory_reservation", "order_id = ? AND status = 'RESERVED'", orderId))
        .isEqualTo(1);
  }

  private void seedStock(String sku, int availableQuantity) {
    productRepository.save(new Product(UUID.randomUUID(), sku, "Test product " + sku, null));
    StockLevel stock = new StockLevel(UUID.randomUUID(), sku);
    stock.adjust(availableQuantity);
    stockLevelRepository.save(stock);
  }

  private static String uniqueSku(String label) {
    return "SKU-" + label + "-" + UUID.randomUUID();
  }

  private record TestItem(String sku, int quantity) {}

  private String orderPlacedEnvelopeJson(
      UUID eventId, UUID orderId, UUID correlationId, List<TestItem> items) {
    List<Map<String, Object>> payloadItems =
        items.stream()
            .map(
                item ->
                    (Map<String, Object>)
                        Map.of(
                            "sku",
                            item.sku(),
                            "quantity",
                            item.quantity(),
                            "unitPrice",
                            Map.of("currencyCode", "USD", "amount", "9.99")))
            .toList();
    Map<String, Object> payload =
        Map.of(
            "customerId",
            UUID.randomUUID().toString(),
            "idempotencyKey",
            "test-" + UUID.randomUUID(),
            "items",
            payloadItems,
            "totalAmount",
            Map.of("currencyCode", "USD", "amount", "9.99"));
    EventEnvelope envelope =
        new EventEnvelope(
            eventId,
            "OrderPlaced",
            1,
            Instant.now(),
            correlationId,
            null,
            orderId,
            "order-service",
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

  private int countRows(String table, String where, Object param) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE " + where, Integer.class, param);
    return count == null ? 0 : count;
  }

  private int countRowsBySku(String table, String sku) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE sku = ?", Integer.class, sku);
    return count == null ? 0 : count;
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
