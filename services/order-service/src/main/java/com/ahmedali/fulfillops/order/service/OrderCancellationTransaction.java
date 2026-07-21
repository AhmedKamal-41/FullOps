package com.ahmedali.fulfillops.order.service;

import com.ahmedali.fulfillops.order.domain.Order;
import com.ahmedali.fulfillops.order.domain.OrderCancellation;
import com.ahmedali.fulfillops.order.domain.OrderCancellationReasonCode;
import com.ahmedali.fulfillops.order.domain.OrderCancellationRepository;
import com.ahmedali.fulfillops.order.domain.OrderCancelledReasonCode;
import com.ahmedali.fulfillops.order.domain.OrderRepository;
import com.ahmedali.fulfillops.order.domain.OrderStatus;
import com.ahmedali.fulfillops.order.domain.OrderStatusHistory;
import com.ahmedali.fulfillops.order.domain.OrderStatusHistoryRepository;
import com.ahmedali.fulfillops.order.domain.OrderStatusTransitions;
import com.ahmedali.fulfillops.order.messaging.OutboxEventWriter;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place every cancellation path in this service goes through, whatever triggered it: a
 * customer/operator HTTP request, PaymentDeclined, or a fulfillment cancellation (either a direct
 * operator action on Fulfillment Service or Fulfillment reacting to our own
 * OrderCancellationRequested.v1). An order with nothing ever reserved or charged (still PENDING)
 * cancels immediately — see finalizeDirectly. Every other order waits in CANCELLATION_PENDING for
 * whichever of {inventory release, payment refund, fulfillment cancellation} it actually needs,
 * tracked by one OrderCancellation row — see startOrMerge and the confirm* methods.
 */
@Component
public class OrderCancellationTransaction {

  private static final String CANCELLED_EVENT_TYPE = "OrderCancelled";
  private static final String CANCELLATION_REQUESTED_EVENT_TYPE = "OrderCancellationRequested";
  private static final int EVENT_VERSION = 1;

  private final OrderRepository orderRepository;
  private final OrderStatusHistoryRepository statusHistoryRepository;
  private final OrderCancellationRepository cancellationRepository;
  private final OutboxEventWriter outboxEventWriter;
  private final OperationsProjectionUpdater projectionUpdater;

  public OrderCancellationTransaction(
      OrderRepository orderRepository,
      OrderStatusHistoryRepository statusHistoryRepository,
      OrderCancellationRepository cancellationRepository,
      OutboxEventWriter outboxEventWriter,
      OperationsProjectionUpdater projectionUpdater) {
    this.orderRepository = orderRepository;
    this.statusHistoryRepository = statusHistoryRepository;
    this.cancellationRepository = cancellationRepository;
    this.outboxEventWriter = outboxEventWriter;
    this.projectionUpdater = projectionUpdater;
  }

  /** InventoryRejected, and the zero-compensation case of a customer/operator request. */
  @Transactional
  public void finalizeDirectly(
      UUID orderId,
      OrderCancelledReasonCode reasonCode,
      String reasonDetail,
      UUID correlationId,
      UUID causationId) {
    Order order = orderRepository.findById(orderId).orElseThrow();
    if (order.getStatus() == OrderStatus.CANCELLED) {
      return;
    }
    if (!OrderStatusTransitions.isAllowed(order.getStatus(), OrderStatus.CANCELLED)) {
      throw new OrderMilestoneTooEarlyException(orderId, order.getStatus());
    }

    Instant now = Instant.now();
    order.updateStatus(OrderStatus.CANCELLED);
    orderRepository.save(order);
    statusHistoryRepository.save(
        new OrderStatusHistory(orderId, OrderStatus.CANCELLED, reasonCode.name(), now));
    projectionUpdater.advanceStage(orderId, OrderStatus.CANCELLED, reasonCode.name(), now);
    outboxEventWriter.write(
        CANCELLED_EVENT_TYPE,
        EVENT_VERSION,
        orderId,
        correlationId,
        causationId,
        new CancelledPayload(reasonCode.name(), reasonDetail));
  }

  /**
   * Starts a new compensation-tracked cancellation, or — if one is already in flight for this order
   * — merges in any newly-discovered requirement instead of creating a second row. Required flags
   * only ever go false -> true here, never the reverse, so merging is always safe.
   */
  @Transactional
  public void startOrMerge(
      UUID orderId,
      String requestedBy,
      String reasonDetail,
      OrderCancellationReasonCode reasonCode,
      boolean inventoryReleaseRequired,
      boolean paymentRefundRequired,
      boolean fulfillmentCancelRequired,
      UUID correlationId,
      UUID causationId,
      boolean emitCancellationRequestedEvent) {
    Optional<OrderCancellation> existing = cancellationRepository.findById(orderId);
    if (existing.isPresent()) {
      mergeRequirements(
          existing.get(),
          inventoryReleaseRequired,
          paymentRefundRequired,
          fulfillmentCancelRequired);
      return;
    }

    if (!inventoryReleaseRequired && !paymentRefundRequired && !fulfillmentCancelRequired) {
      finalizeDirectly(
          orderId,
          OrderCancelledReasonCode.valueOf(reasonCode.name()),
          reasonDetail,
          correlationId,
          causationId);
      return;
    }

    Order order = orderRepository.findById(orderId).orElseThrow();
    if (!OrderStatusTransitions.isAllowed(order.getStatus(), OrderStatus.CANCELLATION_PENDING)) {
      if (order.getStatus() == OrderStatus.PENDING) {
        throw new OrderMilestoneTooEarlyException(orderId, order.getStatus());
      }
      // Already CANCELLED/DELIVERED/REQUIRES_REVIEW, or (unexpectedly) DISPATCHED — the order is
      // already resolved one way or another, so there is nothing left to compensate here.
      return;
    }

    Instant now = Instant.now();
    order.updateStatus(OrderStatus.CANCELLATION_PENDING);
    orderRepository.save(order);
    statusHistoryRepository.save(
        new OrderStatusHistory(orderId, OrderStatus.CANCELLATION_PENDING, reasonCode.name(), now));
    projectionUpdater.advanceStage(
        orderId, OrderStatus.CANCELLATION_PENDING, reasonCode.name(), now);
    cancellationRepository.save(
        new OrderCancellation(
            orderId,
            requestedBy,
            reasonDetail,
            reasonCode,
            inventoryReleaseRequired,
            paymentRefundRequired,
            fulfillmentCancelRequired));

    if (emitCancellationRequestedEvent) {
      outboxEventWriter.write(
          CANCELLATION_REQUESTED_EVENT_TYPE,
          EVENT_VERSION,
          orderId,
          correlationId,
          causationId,
          new CancellationRequestedPayload(reasonCode.name(), reasonDetail));
    }
  }

  /**
   * ReconciliationService's one safe recovery action for a cancellation stuck past its threshold: a
   * verbatim re-publish of OrderCancellationRequested.v1. Safe because every consumer of that event
   * checks its own local state before acting — a repeat is a no-op wherever the compensation
   * already happened, and a useful nudge wherever it didn't (e.g. the original attempt itself
   * dead-lettered).
   */
  @Transactional
  public void republishCancellationRequested(
      UUID orderId, String reasonDetail, UUID correlationId) {
    outboxEventWriter.write(
        CANCELLATION_REQUESTED_EVENT_TYPE,
        EVENT_VERSION,
        orderId,
        correlationId,
        null,
        new CancellationRequestedPayload("RECONCILIATION_RETRY", reasonDetail));
  }

  @Transactional
  public void confirmInventoryRelease(UUID orderId, UUID correlationId, UUID causationId) {
    OrderCancellation tracker = requireTracker(orderId);
    if (tracker.isResolved()) {
      return;
    }
    tracker.confirmInventoryRelease();
    cancellationRepository.save(tracker);
    tryFinalize(tracker, correlationId, causationId);
  }

  @Transactional
  public void confirmPaymentRefund(UUID orderId, UUID correlationId, UUID causationId) {
    OrderCancellation tracker = requireTracker(orderId);
    if (tracker.isResolved()) {
      return;
    }
    tracker.confirmPaymentRefund();
    cancellationRepository.save(tracker);
    tryFinalize(tracker, correlationId, causationId);
  }

  @Transactional
  public void confirmFulfillmentCancel(UUID orderId, UUID correlationId, UUID causationId) {
    OrderCancellation tracker = requireTracker(orderId);
    if (tracker.isResolved()) {
      return;
    }
    tracker.confirmFulfillmentCancel();
    cancellationRepository.save(tracker);
    tryFinalize(tracker, correlationId, causationId);
  }

  /**
   * Called when a milestone event (InventoryReserved/PaymentAuthorized/FulfillmentAssigned) arrives
   * for an order that has already moved to CANCELLATION_PENDING — an out-of-order delivery, since
   * these travel on a different topic than whatever started the cancellation. It means Order
   * Service now knows this compensation genuinely is required, even though it wasn't known to be at
   * the moment cancellation started.
   */
  @Transactional
  public void foldInventoryReleaseRequirement(UUID orderId) {
    OrderCancellation tracker = requireTracker(orderId);
    tracker.requireInventoryRelease();
    cancellationRepository.save(tracker);
  }

  @Transactional
  public void foldPaymentRefundRequirement(UUID orderId) {
    OrderCancellation tracker = requireTracker(orderId);
    tracker.requirePaymentRefund();
    cancellationRepository.save(tracker);
  }

  @Transactional
  public void foldFulfillmentCancelRequirement(UUID orderId) {
    OrderCancellation tracker = requireTracker(orderId);
    tracker.requireFulfillmentCancel();
    cancellationRepository.save(tracker);
  }

  private void mergeRequirements(
      OrderCancellation tracker,
      boolean inventoryReleaseRequired,
      boolean paymentRefundRequired,
      boolean fulfillmentCancelRequired) {
    if (inventoryReleaseRequired) {
      tracker.requireInventoryRelease();
    }
    if (paymentRefundRequired) {
      tracker.requirePaymentRefund();
    }
    if (fulfillmentCancelRequired) {
      tracker.requireFulfillmentCancel();
    }
    cancellationRepository.save(tracker);
  }

  private void tryFinalize(OrderCancellation tracker, UUID correlationId, UUID causationId) {
    if (!tracker.isFullyConfirmed()) {
      return;
    }
    UUID orderId = tracker.getOrderId();
    Order order = orderRepository.findById(orderId).orElseThrow();
    if (order.getStatus() != OrderStatus.CANCELLATION_PENDING) {
      return;
    }

    Instant now = Instant.now();
    order.updateStatus(OrderStatus.CANCELLED);
    orderRepository.save(order);
    statusHistoryRepository.save(
        new OrderStatusHistory(
            orderId, OrderStatus.CANCELLED, tracker.getCancellationReasonCode().name(), now));
    projectionUpdater.advanceStage(
        orderId, OrderStatus.CANCELLED, tracker.getCancellationReasonCode().name(), now);
    tracker.markResolved();
    cancellationRepository.save(tracker);

    outboxEventWriter.write(
        CANCELLED_EVENT_TYPE,
        EVENT_VERSION,
        orderId,
        correlationId,
        causationId,
        new CancelledPayload(
            tracker.getCancellationReasonCode().name(), tracker.getReasonDetail()));
  }

  private OrderCancellation requireTracker(UUID orderId) {
    return cancellationRepository
        .findById(orderId)
        .orElseThrow(() -> new OrderCancellationNotYetTrackedException(orderId));
  }

  private record CancelledPayload(String reasonCode, String reasonDetail) {}

  private record CancellationRequestedPayload(String reasonCode, String reasonDetail) {}
}
