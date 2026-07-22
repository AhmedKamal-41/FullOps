package com.ahmedali.fulfillops.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.ahmedali.fulfillops.inventory.config.TestSecurityConfig;
import com.ahmedali.fulfillops.inventory.config.TestcontainersConfiguration;
import com.ahmedali.fulfillops.inventory.domain.Product;
import com.ahmedali.fulfillops.inventory.domain.ProductRepository;
import com.ahmedali.fulfillops.inventory.domain.StockLevel;
import com.ahmedali.fulfillops.inventory.domain.StockLevelRepository;
import com.ahmedali.fulfillops.inventory.messaging.EventEnvelope;
import com.ahmedali.fulfillops.inventory.messaging.FulfillmentCancelledListener;
import com.ahmedali.fulfillops.inventory.messaging.OrderEventsListener;
import com.ahmedali.fulfillops.inventory.messaging.PaymentDeclinedListener;
import java.time.Instant;
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
 * Covers the autonomous compensation listeners: PaymentDeclinedListener,
 * FulfillmentCancelledListener, and OrderEventsListener's OrderCancellationRequested handling. All
 * three converge on the same release path and are each a safe no-op when there's nothing to
 * release.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class CancellationCompensationIT {

  @Autowired private OrderEventsListener orderEventsListener;
  @Autowired private PaymentDeclinedListener paymentDeclinedListener;
  @Autowired private FulfillmentCancelledListener fulfillmentCancelledListener;
  @Autowired private ProductRepository productRepository;
  @Autowired private StockLevelRepository stockLevelRepository;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void paymentDeclinedReleasesAnExistingReservation() {
    String sku = uniqueSku("PAYMENT-DECLINED");
    seedStock(sku, 10);
    UUID orderId = UUID.randomUUID();
    reserve(orderId, sku, 4);
    assertThat(availableQuantity(sku)).isEqualTo(6);

    paymentDeclinedListener.onMessage(
        envelope(
            "PaymentDeclined",
            orderId,
            Map.of(
                "amount",
                Map.of("currencyCode", "USD", "amount", "9.99"),
                "reasonCode",
                "SIMULATED_CARD_DECLINED")));

    assertThat(availableQuantity(sku)).isEqualTo(10);
  }

  @Test
  void paymentDeclinedWithNoReservationIsANoOp() {
    paymentDeclinedListener.onMessage(
        envelope(
            "PaymentDeclined",
            UUID.randomUUID(),
            Map.of(
                "amount",
                Map.of("currencyCode", "USD", "amount", "9.99"),
                "reasonCode",
                "SIMULATED_CARD_DECLINED")));
    // No exception, nothing to assert beyond "didn't throw" — there is no reservation to inspect.
  }

  @Test
  void fulfillmentCancelledReleasesAnExistingReservation() {
    String sku = uniqueSku("FULFILLMENT-CANCELLED");
    seedStock(sku, 10);
    UUID orderId = UUID.randomUUID();
    reserve(orderId, sku, 3);

    fulfillmentCancelledListener.onMessage(
        envelope(
            "FulfillmentStatusChanged",
            orderId,
            Map.of(
                "fulfillmentId",
                UUID.randomUUID().toString(),
                "previousStatus",
                "PICKING",
                "newStatus",
                "CANCELLED")));

    assertThat(availableQuantity(sku)).isEqualTo(10);
  }

  @Test
  void fulfillmentCancelledIgnoresNonCancelledStatusChanges() {
    String sku = uniqueSku("FULFILLMENT-PACKED");
    seedStock(sku, 10);
    UUID orderId = UUID.randomUUID();
    reserve(orderId, sku, 3);

    fulfillmentCancelledListener.onMessage(
        envelope(
            "FulfillmentStatusChanged",
            orderId,
            Map.of(
                "fulfillmentId",
                UUID.randomUUID().toString(),
                "previousStatus",
                "PICKING",
                "newStatus",
                "PACKED")));

    assertThat(availableQuantity(sku)).isEqualTo(7);
  }

  @Test
  void orderCancellationRequestedReleasesAnExistingReservation() {
    String sku = uniqueSku("ORDER-CANCELLED");
    seedStock(sku, 10);
    UUID orderId = UUID.randomUUID();
    reserve(orderId, sku, 2);

    orderEventsListener.onMessage(
        envelope(
            "OrderCancellationRequested",
            orderId,
            Map.of("reasonCode", "CUSTOMER_REQUESTED", "reasonDetail", "changed their mind")));

    assertThat(availableQuantity(sku)).isEqualTo(10);
  }

  private void reserve(UUID orderId, String sku, int quantity) {
    Map<String, Object> item =
        Map.of(
            "sku", sku,
            "quantity", quantity,
            "unitPrice", Map.of("currencyCode", "USD", "amount", "9.99"));
    Map<String, Object> payload =
        Map.of(
            "customerId", UUID.randomUUID().toString(),
            "idempotencyKey", "test-" + UUID.randomUUID(),
            "items", List.of(item),
            "totalAmount", Map.of("currencyCode", "USD", "amount", "9.99"));
    orderEventsListener.onMessage(envelope("OrderPlaced", orderId, payload));
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

  private int availableQuantity(String sku) {
    return stockLevelRepository.findBySku(sku).orElseThrow().getAvailableQuantity();
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
