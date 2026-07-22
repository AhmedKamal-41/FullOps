package com.ahmedali.fulfillops.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ahmedali.fulfillops.order.config.TestSecurityConfig;
import com.ahmedali.fulfillops.order.config.TestcontainersConfiguration;
import com.ahmedali.fulfillops.order.domain.IncidentKind;
import com.ahmedali.fulfillops.order.domain.IncidentStatus;
import com.ahmedali.fulfillops.order.domain.OperationsIncident;
import com.ahmedali.fulfillops.order.domain.OperationsIncidentRepository;
import com.ahmedali.fulfillops.order.domain.Order;
import com.ahmedali.fulfillops.order.domain.OrderCancellationReasonCode;
import com.ahmedali.fulfillops.order.domain.OrderOperationsProjection;
import com.ahmedali.fulfillops.order.domain.OrderOperationsProjectionRepository;
import com.ahmedali.fulfillops.order.domain.OrderRepository;
import com.ahmedali.fulfillops.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Covers reconciliation directly against real Postgres/Kafka: a cancellation
 * stuck past its threshold gets exactly one safe retry before escalating to REQUIRES_REVIEW, a
 * happy-path order stuck past its threshold escalates straight away, and the advisory lock actually
 * stops two concurrent reconcile() calls from double-processing the same stuck order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class ReconciliationServiceIT {

  @Autowired private ReconciliationService reconciliationService;
  @Autowired private OrderCancellationTransaction cancellationTransaction;
  @Autowired private OrderRepository orderRepository;
  @Autowired private OrderOperationsProjectionRepository projectionRepository;
  @Autowired private OperationsIncidentRepository incidentRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void aStuckCancellationGetsOneRetryThenEscalatesOnASecondStuckPass() {
    Order order = seedOrder(OrderStatus.PAYMENT_AUTHORIZED);
    cancellationTransaction.startOrMerge(
        order.getOrderId(),
        "customer",
        "changed their mind",
        OrderCancellationReasonCode.CUSTOMER_REQUESTED,
        true,
        true,
        false,
        UUID.randomUUID(),
        null,
        true);
    ageCancellationRequestedAt(order.getOrderId(), Duration.ofMinutes(11));

    reconciliationService.reconcile();

    assertThat(status(order.getOrderId())).isEqualTo(OrderStatus.CANCELLATION_PENDING);
    assertThat(openIncident(order.getOrderId(), IncidentKind.CANCELLATION_STUCK)).isPresent();

    // Still stuck (the retry didn't actually get confirmed by anything in this test), and the
    // CANCELLATION_STUCK incident from the first pass is still open, so the second pass escalates.
    ageCancellationRequestedAt(order.getOrderId(), Duration.ofMinutes(11));
    reconciliationService.reconcile();

    assertThat(status(order.getOrderId())).isEqualTo(OrderStatus.REQUIRES_REVIEW);
    assertThat(openIncident(order.getOrderId(), IncidentKind.COMPENSATION_EXHAUSTED)).isPresent();
    assertThat(incidentRepository.findAll())
        .filteredOn(
            incident ->
                incident.getOrderId().equals(order.getOrderId())
                    && incident.getKind() == IncidentKind.CANCELLATION_STUCK)
        .as("the first pass's incident must be reused, not duplicated, on the second pass")
        .hasSize(1);
  }

  @Test
  void aStuckHappyPathOrderEscalatesToRequiresReview() {
    Order order = seedOrder(OrderStatus.INVENTORY_RESERVED);
    ageOrderUpdatedAt(order.getOrderId(), Duration.ofMinutes(31));

    reconciliationService.reconcile();

    assertThat(status(order.getOrderId())).isEqualTo(OrderStatus.REQUIRES_REVIEW);
    assertThat(openIncident(order.getOrderId(), IncidentKind.COMPENSATION_EXHAUSTED)).isPresent();
  }

  @Test
  void concurrentReconcileCallsNeverBothActOnTheSameStuckOrder() throws Exception {
    Order order = seedOrder(OrderStatus.PICKING);
    ageOrderUpdatedAt(order.getOrderId(), Duration.ofMinutes(31));

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<?>> futures =
          IntStream.range(0, 2)
              .<Future<?>>mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            ready.countDown();
                            go.await();
                            reconciliationService.reconcile();
                            return null;
                          }))
              .toList();
      ready.await();
      go.countDown();
      for (Future<?> future : futures) {
        future.get();
      }
    } finally {
      pool.shutdown();
    }

    assertThat(status(order.getOrderId())).isEqualTo(OrderStatus.REQUIRES_REVIEW);
    assertThat(incidentRepository.findAll())
        .filteredOn(incident -> incident.getOrderId().equals(order.getOrderId()))
        .as("only one of the two concurrent runs may have actually processed this order")
        .hasSize(1);
  }

  private Order seedOrder(OrderStatus status) {
    Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "USD", new BigDecimal("10.00"));
    order.updateStatus(status);
    orderRepository.save(order);
    // advanceStage assumes onOrderPlaced already created this row, exactly like production's
    // OrderCreationTransaction always does — seed it directly here since this test bypasses that.
    projectionRepository.save(
        new OrderOperationsProjection(
            order.getOrderId(),
            order.getCustomerId(),
            status,
            order.getCurrencyCode(),
            order.getTotalAmount(),
            order.getCreatedAt()));
    return order;
  }

  private OrderStatus status(UUID orderId) {
    return orderRepository.findById(orderId).orElseThrow().getStatus();
  }

  private Optional<OperationsIncident> openIncident(UUID orderId, IncidentKind kind) {
    return incidentRepository.findByOrderIdAndKindAndStatus(orderId, kind, IncidentStatus.OPEN);
  }

  private void ageOrderUpdatedAt(UUID orderId, Duration age) {
    jdbcTemplate.update(
        "UPDATE orders SET updated_at = ? WHERE order_id = ?",
        Timestamp.from(Instant.now().minus(age)),
        orderId);
  }

  private void ageCancellationRequestedAt(UUID orderId, Duration age) {
    jdbcTemplate.update(
        "UPDATE order_cancellation SET requested_at = ? WHERE order_id = ?",
        Timestamp.from(Instant.now().minus(age)),
        orderId);
  }
}
