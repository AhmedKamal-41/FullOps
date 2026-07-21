package com.ahmedali.fulfillops.order.service;

import com.ahmedali.fulfillops.order.domain.IncidentStatus;
import com.ahmedali.fulfillops.order.domain.OperationsIncidentRepository;
import com.ahmedali.fulfillops.order.domain.Order;
import com.ahmedali.fulfillops.order.domain.OrderOperationsProjection;
import com.ahmedali.fulfillops.order.domain.OrderOperationsProjectionRepository;
import com.ahmedali.fulfillops.order.domain.OrderRepository;
import com.ahmedali.fulfillops.order.domain.OrderStageDuration;
import com.ahmedali.fulfillops.order.domain.OrderStageDurationRepository;
import com.ahmedali.fulfillops.order.domain.OrderStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps order_operations_projection/order_stage_duration in sync with every lifecycle change this
 * service already applies to orders/order_status_history — called from inside the
 * same @Transactional methods (OrderCreationTransaction, OrderLifecycleTransaction,
 * OrderCancellationTransaction, OrderRequiresReviewTransaction) that already write those tables, so
 * projection writes inherit idempotency for free from the calling listener's inbox dedup check —
 * there is no separate idempotency mechanism here. OperationsProjectionRebuildService is the only
 * other writer of these two tables, and only while the ordinary write path above is paused for a
 * rebuild.
 */
@Component
public class OperationsProjectionUpdater {

  private final OrderRepository orderRepository;
  private final OrderOperationsProjectionRepository projectionRepository;
  private final OrderStageDurationRepository stageDurationRepository;
  private final OperationsIncidentRepository incidentRepository;

  public OperationsProjectionUpdater(
      OrderRepository orderRepository,
      OrderOperationsProjectionRepository projectionRepository,
      OrderStageDurationRepository stageDurationRepository,
      OperationsIncidentRepository incidentRepository) {
    this.orderRepository = orderRepository;
    this.projectionRepository = projectionRepository;
    this.stageDurationRepository = stageDurationRepository;
    this.incidentRepository = incidentRepository;
  }

  @Transactional
  public void onOrderPlaced(Order order) {
    projectionRepository.save(
        new OrderOperationsProjection(
            order.getOrderId(),
            order.getCustomerId(),
            order.getStatus(),
            order.getCurrencyCode(),
            order.getTotalAmount(),
            order.getCreatedAt()));
    stageDurationRepository.save(
        new OrderStageDuration(order.getOrderId(), order.getStatus(), order.getCreatedAt()));
  }

  /**
   * reasonCode is nullable and means different things depending on newStatus — see
   * OrderOperationsProjection.advanceStage's Javadoc. now is caller-supplied — the same instant the
   * caller used for this transition's OrderStatusHistory row — so order_status_history and
   * order_stage_duration/order_operations_projection always agree on exactly when a transition
   * happened, which is what makes OperationsProjectionRebuildService's replay exact.
   */
  @Transactional
  public void advanceStage(UUID orderId, OrderStatus newStatus, String reasonCode, Instant now) {
    OrderOperationsProjection projection = projectionRepository.findById(orderId).orElseThrow();
    if (projection.getStatus() == newStatus) {
      return; // already applied — a harmless duplicate, same defense-in-depth every *Transaction
      // in this service already relies on.
    }

    stageDurationRepository
        .findByOrderIdAndStage(orderId, projection.getStatus())
        .ifPresent(current -> current.close(now));
    stageDurationRepository.save(new OrderStageDuration(orderId, newStatus, now));

    projection.advanceStage(newStatus, reasonCode, now);
    projectionRepository.save(projection);
  }

  // Also persisted onto Order itself (see V4__operations.sql) — an event payload is the only
  // place this fact exists, so if it lived only on the projection, a rebuild could never recover
  // it. Order is the durable source rebuild reads from; the projection copy exists purely so the
  // work queue/KPI reads never have to join against orders for it.
  @Transactional
  public void recordInventoryRejection(UUID orderId, String reasonCode) {
    Order order = orderRepository.findById(orderId).orElseThrow();
    order.recordInventoryRejection(reasonCode);
    orderRepository.save(order);

    OrderOperationsProjection projection = projectionRepository.findById(orderId).orElseThrow();
    projection.recordInventoryRejection(reasonCode);
    projectionRepository.save(projection);
  }

  @Transactional
  public void recordPaymentOutcome(
      UUID orderId, String declineReasonCode, int precedingTechnicalFailureCount) {
    Order order = orderRepository.findById(orderId).orElseThrow();
    order.recordPaymentOutcome(declineReasonCode, precedingTechnicalFailureCount);
    orderRepository.save(order);

    OrderOperationsProjection projection = projectionRepository.findById(orderId).orElseThrow();
    projection.recordPaymentOutcome(declineReasonCode, precedingTechnicalFailureCount);
    projectionRepository.save(projection);
  }

  /**
   * Recomputed from operations_incident directly rather than incremented/decremented at each call
   * site — a plain COUNT can't drift out of sync the way a running +1/-1 tally could.
   */
  @Transactional
  public void recalculateOpenIncidentCount(UUID orderId) {
    OrderOperationsProjection projection = projectionRepository.findById(orderId).orElseThrow();
    int openCount = incidentRepository.countByOrderIdAndStatusNot(orderId, IncidentStatus.RESOLVED);
    projection.setOpenIncidentCount(openCount);
    projectionRepository.save(projection);
  }
}
