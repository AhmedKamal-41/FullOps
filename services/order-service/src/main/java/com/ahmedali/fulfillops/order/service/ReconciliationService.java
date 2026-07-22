package com.ahmedali.fulfillops.order.service;

import com.ahmedali.fulfillops.order.domain.IncidentKind;
import com.ahmedali.fulfillops.order.domain.IncidentStatus;
import com.ahmedali.fulfillops.order.domain.OperationsIncidentRepository;
import com.ahmedali.fulfillops.order.domain.Order;
import com.ahmedali.fulfillops.order.domain.OrderCancellation;
import com.ahmedali.fulfillops.order.domain.OrderCancellationRepository;
import com.ahmedali.fulfillops.order.domain.OrderRepository;
import com.ahmedali.fulfillops.order.domain.OrderRequiresReviewReasonCode;
import com.ahmedali.fulfillops.order.domain.OrderStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Finds orders that have stopped making progress and either safely nudges them forward or escalates
 * to a human. Guarded by a Postgres session-scoped advisory lock (see reconcile()) so running more
 * than one instance of this service never causes two replicas to act on the same stuck order at
 * once — a single, fixed lock key is enough since this whole method is one short, sequential unit
 * of work, not something that needs per-order locking. The lock is acquired and released on one
 * JDBC Connection held for the whole method, borrowed directly from the DataSource rather than
 * through JdbcTemplate — an advisory lock belongs to whichever database session acquired it, and
 * JdbcTemplate borrows a fresh pooled connection for every call outside of a transaction, so
 * acquiring and releasing through it could easily end up on two different sessions and leave the
 * lock stuck held. The rest of the method's database work (via the repositories and *Transaction
 * beans below) keeps using its own ordinary, independent transactions on other pooled connections,
 * exactly like every other orchestration method in this service — this connection is only ever
 * touched for the lock itself.
 */
@Service
public class ReconciliationService {

  private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
  private static final long ADVISORY_LOCK_KEY = 8_772_364_981L;

  // Every non-terminal, non-CANCELLATION_PENDING status: CANCELLATION_PENDING has its own,
  // shorter threshold and its own recovery action (see reconcileStuckCancellations).
  private static final List<OrderStatus> HAPPY_PATH_NONTERMINAL_STATUSES =
      List.of(
          OrderStatus.PENDING,
          OrderStatus.INVENTORY_RESERVED,
          OrderStatus.PAYMENT_AUTHORIZED,
          OrderStatus.FULFILLMENT_ASSIGNED,
          OrderStatus.PICKING,
          OrderStatus.PACKED,
          OrderStatus.DISPATCHED);

  private final DataSource dataSource;
  private final OrderRepository orderRepository;
  private final OrderCancellationRepository cancellationRepository;
  private final OperationsIncidentRepository incidentRepository;
  private final IncidentService incidentService;
  private final OrderCancellationTransaction cancellationTransaction;
  private final OrderRequiresReviewTransaction requiresReviewTransaction;
  private final MeterRegistry meterRegistry;
  private final Duration stuckThreshold;
  private final Duration cancellationStuckThreshold;

  public ReconciliationService(
      DataSource dataSource,
      OrderRepository orderRepository,
      OrderCancellationRepository cancellationRepository,
      OperationsIncidentRepository incidentRepository,
      IncidentService incidentService,
      OrderCancellationTransaction cancellationTransaction,
      OrderRequiresReviewTransaction requiresReviewTransaction,
      MeterRegistry meterRegistry,
      @Value("${app.reconciliation.stuck-threshold}") Duration stuckThreshold,
      @Value("${app.reconciliation.cancellation-stuck-threshold}")
          Duration cancellationStuckThreshold) {
    this.dataSource = dataSource;
    this.orderRepository = orderRepository;
    this.cancellationRepository = cancellationRepository;
    this.incidentRepository = incidentRepository;
    this.incidentService = incidentService;
    this.cancellationTransaction = cancellationTransaction;
    this.requiresReviewTransaction = requiresReviewTransaction;
    this.meterRegistry = meterRegistry;
    this.stuckThreshold = stuckThreshold;
    this.cancellationStuckThreshold = cancellationStuckThreshold;
  }

  public void reconcile() {
    Timer.Sample sample = Timer.start(meterRegistry);
    String outcome = "completed";
    try (Connection lockConnection = dataSource.getConnection()) {
      if (!tryAcquireLock(lockConnection)) {
        log.debug("another instance already holds the reconciliation lock, skipping this run");
        outcome = "skipped";
        return;
      }
      try {
        reconcileStuckCancellations();
        reconcileStuckHappyPathOrders();
      } finally {
        releaseLock(lockConnection);
      }
    } catch (SQLException e) {
      outcome = "failed";
      throw new IllegalStateException("failed to acquire the reconciliation advisory lock", e);
    } catch (RuntimeException e) {
      outcome = "failed";
      throw e;
    } finally {
      sample.stop(meterRegistry.timer("reconciliation.run", "outcome", outcome));
    }
  }

  private void reconcileStuckCancellations() {
    Instant cutoff = Instant.now().minus(cancellationStuckThreshold);
    for (OrderCancellation tracker :
        cancellationRepository.findByResolvedAtIsNullAndRequestedAtBefore(cutoff)) {
      handleStuckCancellation(tracker);
    }
  }

  private void handleStuckCancellation(OrderCancellation tracker) {
    UUID orderId = tracker.getOrderId();
    boolean alreadyRetried =
        incidentRepository
            .findByOrderIdAndKindAndStatus(
                orderId, IncidentKind.CANCELLATION_STUCK, IncidentStatus.OPEN)
            .isPresent();
    meterRegistry
        .counter("reconciliation.stuck.orders", "stage", "CANCELLATION_PENDING")
        .increment();

    if (!alreadyRetried) {
      log.info("cancellation stuck for order {}, attempting one safe recovery retry", orderId);
      incidentService.openOrDeduplicate(
          orderId,
          IncidentKind.CANCELLATION_STUCK,
          "cancellation pending since "
              + tracker.getRequestedAt()
              + ", waiting on: "
              + outstanding(tracker));
      cancellationTransaction.republishCancellationRequested(
          orderId, tracker.getReasonDetail(), UUID.randomUUID());
      meterRegistry.counter("reconciliation.recovery.outcome", "outcome", "retried").increment();
      return;
    }

    log.warn("cancellation still stuck for order {} after a recovery retry, escalating", orderId);
    requiresReviewTransaction.markRequiresReview(
        orderId,
        OrderRequiresReviewReasonCode.COMPENSATION_EXHAUSTED,
        "cancellation compensation did not complete after a retried request: "
            + outstanding(tracker),
        UUID.randomUUID(),
        null);
    incidentService.openOrDeduplicate(
        orderId,
        IncidentKind.COMPENSATION_EXHAUSTED,
        "cancellation compensation exhausted: " + outstanding(tracker));
    meterRegistry.counter("reconciliation.recovery.outcome", "outcome", "escalated").increment();
  }

  private void reconcileStuckHappyPathOrders() {
    Instant cutoff = Instant.now().minus(stuckThreshold);
    for (Order order :
        orderRepository.findByStatusInAndUpdatedAtBefore(HAPPY_PATH_NONTERMINAL_STATUSES, cutoff)) {
      log.warn(
          "order {} has been stuck in {} beyond the reconciliation threshold",
          order.getOrderId(),
          order.getStatus());
      requiresReviewTransaction.markRequiresReview(
          order.getOrderId(),
          OrderRequiresReviewReasonCode.COMPENSATION_EXHAUSTED,
          "no progress observed while " + order.getStatus() + " beyond the configured threshold",
          UUID.randomUUID(),
          null);
      incidentService.openOrDeduplicate(
          order.getOrderId(),
          IncidentKind.COMPENSATION_EXHAUSTED,
          "stuck in " + order.getStatus() + " since " + order.getUpdatedAt());
      meterRegistry
          .counter("reconciliation.stuck.orders", "stage", order.getStatus().name())
          .increment();
      meterRegistry.counter("reconciliation.recovery.outcome", "outcome", "escalated").increment();
    }
  }

  private static String outstanding(OrderCancellation tracker) {
    StringBuilder outstanding = new StringBuilder();
    if (tracker.isInventoryReleaseRequired()) {
      outstanding.append("inventory release, ");
    }
    if (tracker.isPaymentRefundRequired()) {
      outstanding.append("payment refund, ");
    }
    if (tracker.isFulfillmentCancelRequired()) {
      outstanding.append("fulfillment cancellation, ");
    }
    return outstanding.isEmpty() ? "nothing (already fully confirmed)" : outstanding.toString();
  }

  private boolean tryAcquireLock(Connection connection) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
      statement.setLong(1, ADVISORY_LOCK_KEY);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getBoolean(1);
      }
    }
  }

  private void releaseLock(Connection connection) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
      statement.setLong(1, ADVISORY_LOCK_KEY);
      statement.execute();
    }
  }
}
