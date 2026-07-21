package com.ahmedali.fulfillops.fulfillment.domain;

import java.util.Map;
import java.util.Set;

/**
 * The allowed forward-advance transitions from docs/DOMAIN_MODEL.md's fulfillment state machine,
 * made explicit and checkable in code — the same shape as order-service's OrderStatusTransitions.
 * Cancellation is deliberately not part of this table: it has its own rule (allowed from any
 * non-terminal, pre-DISPATCHED status) enforced separately by FulfillmentTransition, since /cancel
 * is a distinct command from /status.
 */
public final class FulfillmentStatusTransitions {

  private static final Map<FulfillmentStatus, Set<FulfillmentStatus>> ALLOWED =
      Map.of(
          FulfillmentStatus.ASSIGNED, Set.of(FulfillmentStatus.PICKING),
          FulfillmentStatus.PICKING, Set.of(FulfillmentStatus.PACKED),
          FulfillmentStatus.PACKED, Set.of(FulfillmentStatus.DISPATCHED),
          FulfillmentStatus.DISPATCHED, Set.of(FulfillmentStatus.DELIVERED),
          FulfillmentStatus.DELIVERED, Set.of(),
          FulfillmentStatus.CANCELLED, Set.of());

  private static final Set<FulfillmentStatus> CANCELLABLE_STATUSES =
      Set.of(FulfillmentStatus.ASSIGNED, FulfillmentStatus.PICKING, FulfillmentStatus.PACKED);

  private FulfillmentStatusTransitions() {}

  public static boolean isAllowedAdvance(FulfillmentStatus from, FulfillmentStatus to) {
    return ALLOWED.get(from).contains(to);
  }

  public static boolean isCancellable(FulfillmentStatus status) {
    return CANCELLABLE_STATUSES.contains(status);
  }
}
