package com.ahmedali.fulfillops.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ahmedali.fulfillops.order.config.TestSecurityConfig;
import com.ahmedali.fulfillops.order.config.TestcontainersConfiguration;
import com.ahmedali.fulfillops.order.domain.Order;
import com.ahmedali.fulfillops.order.domain.OrderCancellationReasonCode;
import com.ahmedali.fulfillops.order.domain.OrderCancellationRepository;
import com.ahmedali.fulfillops.order.domain.OrderOperationsProjection;
import com.ahmedali.fulfillops.order.domain.OrderOperationsProjectionRepository;
import com.ahmedali.fulfillops.order.domain.OrderRepository;
import com.ahmedali.fulfillops.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;

/**
 * Proves order_cancellation's optimistic lock (version) actually catches the real race this table
 * is exposed to: inventory release, payment refund, and fulfillment cancellation confirmations each
 * arrive on a different Kafka topic, consumed by a different listener thread, so all three can
 * genuinely race to confirm the same tracker at once. Without a version column this was a silent
 * lost update — the row simply ended up missing one confirmation forever, with no exception and no
 * error logged, which is exactly what a live smoke run of the cancellation saga caught. Calling
 * OrderCancellationTransaction directly (not through Kafka) means this
 * test has to do the one retry @RetryableTopic would otherwise do on redelivery — that's the
 * production recovery path, not a workaround specific to this test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class OrderCancellationConcurrencyIT {

  @Autowired private OrderCancellationTransaction cancellationTransaction;
  @Autowired private OrderRepository orderRepository;
  @Autowired private OrderOperationsProjectionRepository projectionRepository;
  @Autowired private OrderCancellationRepository cancellationRepository;

  @Test
  void threeConcurrentConfirmationsAllLandEvenUnderARealRace() throws Exception {
    Order order = seedOrder();
    cancellationTransaction.startOrMerge(
        order.getOrderId(),
        "customer",
        "changed their mind",
        OrderCancellationReasonCode.CUSTOMER_REQUESTED,
        true,
        true,
        true,
        UUID.randomUUID(),
        null,
        true);

    List<Callable<Void>> confirmations =
        List.of(
            () -> {
              cancellationTransaction.confirmInventoryRelease(
                  order.getOrderId(), UUID.randomUUID(), UUID.randomUUID());
              return null;
            },
            () -> {
              cancellationTransaction.confirmPaymentRefund(
                  order.getOrderId(), UUID.randomUUID(), UUID.randomUUID());
              return null;
            },
            () -> {
              cancellationTransaction.confirmFulfillmentCancel(
                  order.getOrderId(), UUID.randomUUID(), UUID.randomUUID());
              return null;
            });

    CountDownLatch ready = new CountDownLatch(confirmations.size());
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(confirmations.size());
    try {
      List<Future<Void>> futures =
          confirmations.stream()
              .map(
                  confirmation ->
                      pool.submit(
                          () -> {
                            ready.countDown();
                            go.await();
                            return confirmation.call();
                          }))
              .toList();
      ready.await();
      go.countDown();

      // A genuine version conflict here is Kafka's own redelivery mechanism's job to retry, not
      // this method's — so this loop stands in for exactly one such redelivery per loser.
      for (Future<Void> future : futures) {
        retryOnceOnLockConflict(future, confirmations);
      }
    } finally {
      pool.shutdown();
    }

    var tracker = cancellationRepository.findById(order.getOrderId()).orElseThrow();
    assertThat(tracker.isFullyConfirmed())
        .as("every confirmation must land, not just whichever won the race")
        .isTrue();
    assertThat(orderRepository.findById(order.getOrderId()).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.CANCELLED);
  }

  private void retryOnceOnLockConflict(Future<Void> future, List<Callable<Void>> confirmations)
      throws Exception {
    try {
      future.get();
    } catch (Exception failed) {
      if (causedByOptimisticLockConflict(failed)) {
        // We don't know which of the three lost the race from here, but retrying every
        // confirmation is exactly what happens in production too: each is independently
        // idempotent (already-confirmed is a no-op), so redelivering all of them is safe.
        for (Callable<Void> confirmation : confirmations) {
          confirmation.call();
        }
        return;
      }
      throw failed;
    }
  }

  private static boolean causedByOptimisticLockConflict(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof OptimisticLockingFailureException) {
        return true;
      }
    }
    return false;
  }

  private Order seedOrder() {
    Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "USD", new BigDecimal("10.00"));
    order.updateStatus(OrderStatus.FULFILLMENT_ASSIGNED);
    orderRepository.save(order);
    // advanceStage assumes onOrderPlaced already created this row, exactly like production's
    // OrderCreationTransaction always does — seed it directly here since this test bypasses that.
    projectionRepository.save(
        new OrderOperationsProjection(
            order.getOrderId(),
            order.getCustomerId(),
            OrderStatus.FULFILLMENT_ASSIGNED,
            order.getCurrencyCode(),
            order.getTotalAmount(),
            order.getCreatedAt()));
    return order;
  }
}
