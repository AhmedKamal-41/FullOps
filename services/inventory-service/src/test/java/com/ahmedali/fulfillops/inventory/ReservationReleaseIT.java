package com.ahmedali.fulfillops.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ahmedali.fulfillops.inventory.config.TestSecurityConfig;
import com.ahmedali.fulfillops.inventory.config.TestcontainersConfiguration;
import com.ahmedali.fulfillops.inventory.domain.AdjustmentSource;
import com.ahmedali.fulfillops.inventory.domain.InventoryAdjustment;
import com.ahmedali.fulfillops.inventory.domain.InventoryAdjustmentRepository;
import com.ahmedali.fulfillops.inventory.domain.Product;
import com.ahmedali.fulfillops.inventory.domain.ProductRepository;
import com.ahmedali.fulfillops.inventory.domain.StockLevel;
import com.ahmedali.fulfillops.inventory.domain.StockLevelRepository;
import com.ahmedali.fulfillops.inventory.messaging.EventEnvelope;
import com.ahmedali.fulfillops.inventory.messaging.OrderPlacedListener;
import com.ahmedali.fulfillops.inventory.service.ReleaseOutcome;
import com.ahmedali.fulfillops.inventory.service.ReleaseReasonCode;
import com.ahmedali.fulfillops.inventory.service.ReservationNotFoundException;
import com.ahmedali.fulfillops.inventory.service.ReservationReleaseService;
import java.time.Instant;
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

/**
 * Covers the "release idempotency" and "audit entries" scenarios for the release side of the
 * reservation state machine (RESERVED -> RELEASED). Not wired to any Kafka consumer this phase —
 * see ReservationReleaseService's Javadoc for why — so these tests call the service directly, the
 * same way a future PaymentDeclined.v1 listener will.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class ReservationReleaseIT {

  @Autowired private OrderPlacedListener orderPlacedListener;
  @Autowired private ReservationReleaseService reservationReleaseService;
  @Autowired private ProductRepository productRepository;
  @Autowired private StockLevelRepository stockLevelRepository;
  @Autowired private InventoryAdjustmentRepository adjustmentRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void releasingAConfirmedReservationAddsStockBackAndFlipsStatus() throws Exception {
    String sku = uniqueSku("RELEASE");
    seedStock(sku, 10);
    UUID orderId = UUID.randomUUID();
    reserve(orderId, sku, 4);
    assertThat(availableQuantity(sku)).isEqualTo(6);

    ReleaseOutcome outcome =
        reservationReleaseService.release(
            orderId, ReleaseReasonCode.PAYMENT_DECLINED, UUID.randomUUID(), UUID.randomUUID());

    assertThat(outcome).isInstanceOf(ReleaseOutcome.Released.class);
    assertThat(availableQuantity(sku)).isEqualTo(10);
    assertThat(reservedQuantity(sku)).isZero();
    assertThat(countRows("inventory_reservation", "order_id = ? AND status = 'RELEASED'", orderId))
        .isEqualTo(1);
  }

  @Test
  void releasingTheSameReservationTwiceNeverAddsStockTwice() throws Exception {
    String sku = uniqueSku("RELEASE-TWICE");
    seedStock(sku, 10);
    UUID orderId = UUID.randomUUID();
    reserve(orderId, sku, 4);

    ReleaseOutcome first =
        reservationReleaseService.release(
            orderId, ReleaseReasonCode.PAYMENT_DECLINED, UUID.randomUUID(), UUID.randomUUID());
    ReleaseOutcome second =
        reservationReleaseService.release(
            orderId, ReleaseReasonCode.PAYMENT_DECLINED, UUID.randomUUID(), UUID.randomUUID());

    assertThat(first).isInstanceOf(ReleaseOutcome.Released.class);
    assertThat(second).isInstanceOf(ReleaseOutcome.AlreadyReleased.class);
    assertThat(availableQuantity(sku)).isEqualTo(10);
    assertThat(countRows("inventory_adjustment", "sku = ? AND source = 'RELEASE'", sku))
        .isEqualTo(1);
    assertThat(
            countRows(
                "outbox_event", "aggregate_id = ? AND event_type = 'InventoryReleased'", orderId))
        .isEqualTo(1);
  }

  @Test
  void releasingAnOrderThatWasNeverReservedThrows() {
    assertThatThrownBy(
            () ->
                reservationReleaseService.release(
                    UUID.randomUUID(),
                    ReleaseReasonCode.PAYMENT_DECLINED,
                    UUID.randomUUID(),
                    UUID.randomUUID()))
        .isInstanceOf(ReservationNotFoundException.class);
  }

  @Test
  void releaseAuditEntryRecordsReasonCorrelationAndBeforeAfterQuantities() throws Exception {
    String sku = uniqueSku("RELEASE-AUDIT");
    seedStock(sku, 10);
    UUID orderId = UUID.randomUUID();
    reserve(orderId, sku, 4);
    UUID correlationId = UUID.randomUUID();

    reservationReleaseService.release(
        orderId, ReleaseReasonCode.FULFILLMENT_CANCELLED, correlationId, UUID.randomUUID());

    InventoryAdjustment adjustment =
        adjustmentRepository.findAll().stream()
            .filter(row -> row.getSku().equals(sku) && row.getSource() == AdjustmentSource.RELEASE)
            .findFirst()
            .orElseThrow();
    assertThat(adjustment.getChangeQuantity()).isEqualTo(4);
    assertThat(adjustment.getQuantityBefore()).isEqualTo(6);
    assertThat(adjustment.getQuantityAfter()).isEqualTo(10);
    assertThat(adjustment.getReasonCode()).isEqualTo("FULFILLMENT_CANCELLED");
    assertThat(adjustment.getActor()).isEqualTo("system");
    assertThat(adjustment.getCorrelationId()).isEqualTo(correlationId);
  }

  private void reserve(UUID orderId, String sku, int quantity) throws Exception {
    orderPlacedListener.onMessage(
        orderPlacedEnvelopeJson(UUID.randomUUID(), orderId, sku, quantity));
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

  private String orderPlacedEnvelopeJson(UUID eventId, UUID orderId, String sku, int quantity) {
    Map<String, Object> payload =
        Map.of(
            "customerId", UUID.randomUUID().toString(),
            "idempotencyKey", "test-" + UUID.randomUUID(),
            "items",
                List.of(
                    Map.of(
                        "sku", sku,
                        "quantity", quantity,
                        "unitPrice", Map.of("currencyCode", "USD", "amount", "9.99"))),
            "totalAmount", Map.of("currencyCode", "USD", "amount", "9.99"));
    EventEnvelope envelope =
        new EventEnvelope(
            eventId,
            "OrderPlaced",
            1,
            Instant.now(),
            UUID.randomUUID(),
            null,
            orderId,
            "order-service",
            objectMapper.valueToTree(payload));
    return objectMapper.writeValueAsString(envelope);
  }

  private int availableQuantity(String sku) {
    return stockLevelRepository.findBySku(sku).orElseThrow().getAvailableQuantity();
  }

  private int reservedQuantity(String sku) {
    return stockLevelRepository.findBySku(sku).orElseThrow().getReservedQuantity();
  }

  private int countRows(String table, String where, Object param) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE " + where, Integer.class, param);
    return count == null ? 0 : count;
  }
}
