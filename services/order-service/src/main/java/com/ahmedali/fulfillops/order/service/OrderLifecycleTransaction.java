package com.ahmedali.fulfillops.order.service;

import com.ahmedali.fulfillops.order.domain.Order;
import com.ahmedali.fulfillops.order.domain.OrderRepository;
import com.ahmedali.fulfillops.order.domain.OrderStatus;
import com.ahmedali.fulfillops.order.domain.OrderStatusHistory;
import com.ahmedali.fulfillops.order.domain.OrderStatusHistoryRepository;
import com.ahmedali.fulfillops.order.domain.OrderStatusTransitions;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies the "everything is going fine" forward milestones (inventory reserved, payment
 * authorized, fulfillment assigned, and each fulfillment status step) to an order's status and
 * history. If cancellation is already pending for the order, a milestone doesn't move status
 * forward at all — it means Order Service has just learned, later than expected, that this
 * compensation genuinely is required, so it folds into the cancellation tracker instead — see
 * OrderCancellationTransaction's fold* methods. FulfillmentStatusChanged with newStatus=CANCELLED
 * is deliberately not handled here — that always goes through OrderCancellationTransaction
 * directly, since it starts or confirms a cancellation rather than advancing one.
 */
@Component
public class OrderLifecycleTransaction {

  // The linear happy-path order, used only to tell "this event arrived too early" (retry later)
  // apart from "this event is stale, the order already moved on" (safe to ignore).
  private static final List<OrderStatus> HAPPY_PATH =
      List.of(
          OrderStatus.PENDING,
          OrderStatus.INVENTORY_RESERVED,
          OrderStatus.PAYMENT_AUTHORIZED,
          OrderStatus.FULFILLMENT_ASSIGNED,
          OrderStatus.PICKING,
          OrderStatus.PACKED,
          OrderStatus.DISPATCHED,
          OrderStatus.DELIVERED);

  private final OrderRepository orderRepository;
  private final OrderStatusHistoryRepository statusHistoryRepository;
  private final OrderCancellationTransaction cancellationTransaction;
  private final OperationsProjectionUpdater projectionUpdater;

  public OrderLifecycleTransaction(
      OrderRepository orderRepository,
      OrderStatusHistoryRepository statusHistoryRepository,
      OrderCancellationTransaction cancellationTransaction,
      OperationsProjectionUpdater projectionUpdater) {
    this.orderRepository = orderRepository;
    this.statusHistoryRepository = statusHistoryRepository;
    this.cancellationTransaction = cancellationTransaction;
    this.projectionUpdater = projectionUpdater;
  }

  @Transactional
  public void onInventoryReserved(UUID orderId) {
    Order order = orderRepository.findById(orderId).orElseThrow();
    if (order.getStatus() == OrderStatus.CANCELLATION_PENDING) {
      cancellationTransaction.foldInventoryReleaseRequirement(orderId);
      return;
    }
    applyForward(order, OrderStatus.INVENTORY_RESERVED);
  }

  @Transactional
  public void onPaymentAuthorized(UUID orderId) {
    Order order = orderRepository.findById(orderId).orElseThrow();
    if (order.getStatus() == OrderStatus.CANCELLATION_PENDING) {
      cancellationTransaction.foldPaymentRefundRequirement(orderId);
      return;
    }
    applyForward(order, OrderStatus.PAYMENT_AUTHORIZED);
  }

  @Transactional
  public void onFulfillmentAssigned(UUID orderId) {
    Order order = orderRepository.findById(orderId).orElseThrow();
    if (order.getStatus() == OrderStatus.CANCELLATION_PENDING) {
      cancellationTransaction.foldFulfillmentCancelRequirement(orderId);
      return;
    }
    applyForward(order, OrderStatus.FULFILLMENT_ASSIGNED);
  }

  /** newStatus is one of PICKING, PACKED, DISPATCHED, DELIVERED — never CANCELLED. */
  @Transactional
  public void onFulfillmentStatusChanged(UUID orderId, OrderStatus newStatus) {
    Order order = orderRepository.findById(orderId).orElseThrow();
    applyForward(order, newStatus);
  }

  private void applyForward(Order order, OrderStatus target) {
    if (order.getStatus() == target) {
      return; // already applied — a harmless duplicate on top of the inbox's own dedup
    }
    if (!OrderStatusTransitions.isAllowed(order.getStatus(), target)) {
      if (isEarlierInHappyPathThan(order.getStatus(), target)) {
        throw new OrderMilestoneTooEarlyException(order.getOrderId(), order.getStatus());
      }
      // The order already moved past this point, was cancelled, or needs review — this milestone
      // is stale and safe to ignore.
      return;
    }

    Instant now = Instant.now();
    order.updateStatus(target);
    orderRepository.save(order);
    statusHistoryRepository.save(new OrderStatusHistory(order.getOrderId(), target, null, now));
    projectionUpdater.advanceStage(order.getOrderId(), target, null, now);
  }

  private static boolean isEarlierInHappyPathThan(OrderStatus current, OrderStatus target) {
    int currentRank = HAPPY_PATH.indexOf(current);
    int targetRank = HAPPY_PATH.indexOf(target);
    return currentRank >= 0 && targetRank >= 0 && currentRank < targetRank;
  }
}
