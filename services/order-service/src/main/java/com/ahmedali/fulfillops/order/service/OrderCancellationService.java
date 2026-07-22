package com.ahmedali.fulfillops.order.service;

import com.ahmedali.fulfillops.order.domain.CancellationIdempotencyRequest;
import com.ahmedali.fulfillops.order.domain.CancellationIdempotencyRequestId;
import com.ahmedali.fulfillops.order.domain.CancellationIdempotencyRequestRepository;
import com.ahmedali.fulfillops.order.domain.IncidentKind;
import com.ahmedali.fulfillops.order.domain.Order;
import com.ahmedali.fulfillops.order.domain.OrderCancellationReasonCode;
import com.ahmedali.fulfillops.order.domain.OrderRepository;
import com.ahmedali.fulfillops.order.domain.OrderRequiresReviewReasonCode;
import com.ahmedali.fulfillops.order.domain.OrderStatus;
import com.ahmedali.fulfillops.order.web.OrderNotFoundException;
import com.ahmedali.fulfillops.order.web.dto.OrderResponse;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Orchestrates POST /api/v1/orders/{orderId}/cancellation-requests. A customer may only cancel
 * their own order; an operator/admin may cancel any order. What happens next depends entirely on
 * the order's current status: DELIVERED can never be cancelled, CANCELLED/REQUIRES_REVIEW/
 * CANCELLATION_PENDING are already resolved one way or another (a safe no-op), DISPATCHED escalates
 * straight to REQUIRES_REVIEW (goods are physically in transit, so this is never automated — see
 * docs/ARCHITECTURE.md), and everything else starts the compensation saga in
 * OrderCancellationTransaction.
 */
@Service
public class OrderCancellationService {

  private final OrderRepository orderRepository;
  private final CancellationIdempotencyRequestRepository idempotencyRepository;
  private final OrderCancellationTransaction cancellationTransaction;
  private final OrderRequiresReviewTransaction requiresReviewTransaction;
  private final IncidentService incidentService;
  private final CancellationIdempotencyTransaction idempotencyTransaction;

  public OrderCancellationService(
      OrderRepository orderRepository,
      CancellationIdempotencyRequestRepository idempotencyRepository,
      OrderCancellationTransaction cancellationTransaction,
      OrderRequiresReviewTransaction requiresReviewTransaction,
      IncidentService incidentService,
      CancellationIdempotencyTransaction idempotencyTransaction) {
    this.orderRepository = orderRepository;
    this.idempotencyRepository = idempotencyRepository;
    this.cancellationTransaction = cancellationTransaction;
    this.requiresReviewTransaction = requiresReviewTransaction;
    this.incidentService = incidentService;
    this.idempotencyTransaction = idempotencyTransaction;
  }

  public OrderResponse requestCancellation(
      UUID orderId,
      String actorId,
      boolean requesterIsStaff,
      String idempotencyKey,
      String reasonDetail,
      UUID correlationId) {
    Order order =
        orderRepository
            .findById(orderId)
            .filter(o -> requesterIsStaff || o.getCustomerId().toString().equals(actorId))
            .orElseThrow(() -> new OrderNotFoundException(orderId));
    if (requesterIsStaff && (reasonDetail == null || reasonDetail.isBlank())) {
      throw new InvalidOrderRequestException(
          "a reason is required when an operator or admin requests cancellation");
    }

    String fingerprint = fingerprint(actorId, orderId, reasonDetail);
    CancellationIdempotencyRequestId idempotencyId =
        new CancellationIdempotencyRequestId(actorId, idempotencyKey);
    Optional<CancellationIdempotencyRequest> existing =
        idempotencyRepository.findById(idempotencyId);
    if (existing.isPresent()) {
      if (!existing.get().getRequestFingerprint().equals(fingerprint)) {
        throw new IdempotencyKeyConflictException(idempotencyKey);
      }
      return currentState(orderId);
    }

    OrderCancellationReasonCode reasonCode =
        requesterIsStaff
            ? OrderCancellationReasonCode.OPERATOR_REQUESTED
            : OrderCancellationReasonCode.CUSTOMER_REQUESTED;
    apply(order, actorId, reasonCode, reasonDetail, correlationId);

    idempotencyTransaction.record(actorId, idempotencyKey, fingerprint, orderId);
    return currentState(orderId);
  }

  private void apply(
      Order order,
      String actorId,
      OrderCancellationReasonCode reasonCode,
      String reasonDetail,
      UUID correlationId) {
    UUID orderId = order.getOrderId();
    switch (order.getStatus()) {
      case DELIVERED ->
          throw new InvalidOrderRequestException(
              "order " + orderId + " has already been delivered and cannot be cancelled");
      case CANCELLED, REQUIRES_REVIEW, CANCELLATION_PENDING -> {
        // Already resolved one way or another — requesting cancellation again is a no-op.
      }
      case DISPATCHED -> {
        requiresReviewTransaction.markRequiresReview(
            orderId,
            OrderRequiresReviewReasonCode.CANCELLATION_AFTER_DISPATCH,
            reasonDetail,
            correlationId,
            null);
        incidentService.openOrDeduplicate(
            orderId,
            IncidentKind.CANCELLATION_AFTER_DISPATCH,
            "cancellation requested after dispatch: " + reasonDetail);
      }
      default -> {
        OrderStatus status = order.getStatus();
        boolean inventoryReleaseRequired = status != OrderStatus.PENDING;
        boolean paymentRefundRequired =
            status == OrderStatus.PAYMENT_AUTHORIZED
                || status == OrderStatus.FULFILLMENT_ASSIGNED
                || status == OrderStatus.PICKING
                || status == OrderStatus.PACKED;
        boolean fulfillmentCancelRequired =
            status == OrderStatus.FULFILLMENT_ASSIGNED
                || status == OrderStatus.PICKING
                || status == OrderStatus.PACKED;
        cancellationTransaction.startOrMerge(
            orderId,
            actorId,
            reasonDetail,
            reasonCode,
            inventoryReleaseRequired,
            paymentRefundRequired,
            fulfillmentCancelRequired,
            correlationId,
            null,
            true);
      }
    }
  }

  private OrderResponse currentState(UUID orderId) {
    return OrderService.toResponse(orderRepository.findById(orderId).orElseThrow());
  }

  private static String fingerprint(String actorId, UUID orderId, String reasonDetail) {
    return RequestFingerprint.sha256Hex(actorId + '|' + orderId + '|' + reasonDetail);
  }
}
