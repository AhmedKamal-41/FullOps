package com.ahmedali.fulfillops.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.ahmedali.fulfillops.inventory.config.TestSecurityConfig;
import com.ahmedali.fulfillops.inventory.config.TestcontainersConfiguration;
import com.ahmedali.fulfillops.inventory.domain.Product;
import com.ahmedali.fulfillops.inventory.domain.ProductRepository;
import com.ahmedali.fulfillops.inventory.domain.StockLevel;
import com.ahmedali.fulfillops.inventory.domain.StockLevelRepository;
import com.ahmedali.fulfillops.inventory.messaging.EventEnvelope;
import com.ahmedali.fulfillops.inventory.messaging.OrderEventsListener;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the no-oversell invariant under real concurrency, not just sequential replay: more
 * distinct orders than available units race to reserve the same SKU. Uses the pool.submit(...) +
 * Future + CountDownLatch pattern from order-service's OrderConcurrencyIT — never
 * ExecutorService.invokeAll(...), which deadlocked that test because it blocks the
 * very thread that must release the latch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class ReservationConcurrencyIT {

  private static final int CONCURRENT_ORDERS = 10;
  private static final int STARTING_STOCK = 5;

  @Autowired private OrderEventsListener orderEventsListener;
  @Autowired private ProductRepository productRepository;
  @Autowired private StockLevelRepository stockLevelRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void concurrentReservationsForTheLastUnitsNeverOversell() throws Exception {
    String sku = "SKU-LIMITED-" + UUID.randomUUID();
    seedStock(sku, STARTING_STOCK);

    CountDownLatch ready = new CountDownLatch(CONCURRENT_ORDERS);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_ORDERS);
    try {
      List<Future<Void>> futures =
          IntStream.range(0, CONCURRENT_ORDERS)
              .<Future<Void>>mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            String envelopeJson =
                                orderPlacedEnvelopeJson(UUID.randomUUID(), UUID.randomUUID(), sku);
                            ready.countDown();
                            go.await();
                            orderEventsListener.onMessage(envelopeJson);
                            return null;
                          }))
              .toList();

      ready.await();
      go.countDown();

      for (Future<Void> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdown();
    }

    StockLevel finalStock = stockLevelRepository.findBySku(sku).orElseThrow();
    assertThat(finalStock.getAvailableQuantity())
        .as("available stock must never go negative and must land at exactly zero once")
        .isZero();
    assertThat(finalStock.getReservedQuantity()).isEqualTo(STARTING_STOCK);
    assertThat(countRowsBySku("reservation_item", sku))
        .as("exactly as many orders as there were units should have won a reservation")
        .isEqualTo(STARTING_STOCK);
  }

  private void seedStock(String sku, int availableQuantity) {
    productRepository.save(new Product(UUID.randomUUID(), sku, "Test product " + sku, null));
    StockLevel stock = new StockLevel(UUID.randomUUID(), sku);
    stock.adjust(availableQuantity);
    stockLevelRepository.save(stock);
  }

  private String orderPlacedEnvelopeJson(UUID eventId, UUID orderId, String sku) {
    Map<String, Object> payload =
        Map.of(
            "customerId", UUID.randomUUID().toString(),
            "idempotencyKey", "test-" + UUID.randomUUID(),
            "items",
                List.of(
                    Map.of(
                        "sku",
                        sku,
                        "quantity",
                        1,
                        "unitPrice",
                        Map.of("currencyCode", "USD", "amount", "9.99"))),
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

  private int countRowsBySku(String table, String sku) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE sku = ?", Integer.class, sku);
    return count == null ? 0 : count;
  }
}
