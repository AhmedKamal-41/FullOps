package com.ahmedali.fulfillops.order.domain;

import java.util.Map;
import java.util.Set;

/**
 * The allowed-transitions table from docs/DOMAIN_MODEL.md's order status state machine, made
 * explicit and checkable in code. Nothing calls isAllowed(...) yet — Phase 4 only ever creates an
 * order in PENDING — but every later phase that moves an order to a new status (inventory reserved,
 * payment authorized, ...) must go through this, not set the status field directly.
 */
public final class OrderStatusTransitions {

  private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED =
      Map.of(
          OrderStatus.PENDING, Set.of(OrderStatus.INVENTORY_RESERVED, OrderStatus.CANCELLED),
          OrderStatus.INVENTORY_RESERVED,
              Set.of(
                  OrderStatus.PAYMENT_AUTHORIZED,
                  OrderStatus.CANCELLED,
                  OrderStatus.REQUIRES_REVIEW),
          OrderStatus.PAYMENT_AUTHORIZED,
              Set.of(OrderStatus.FULFILLMENT_ASSIGNED, OrderStatus.REQUIRES_REVIEW),
          OrderStatus.FULFILLMENT_ASSIGNED,
              Set.of(OrderStatus.PICKING, OrderStatus.CANCELLED, OrderStatus.REQUIRES_REVIEW),
          OrderStatus.PICKING, Set.of(OrderStatus.PACKED, OrderStatus.CANCELLED),
          OrderStatus.PACKED, Set.of(OrderStatus.DISPATCHED, OrderStatus.CANCELLED),
          OrderStatus.DISPATCHED, Set.of(OrderStatus.DELIVERED),
          OrderStatus.DELIVERED, Set.of(),
          OrderStatus.CANCELLED, Set.of(),
          OrderStatus.REQUIRES_REVIEW, Set.of(OrderStatus.CANCELLED));

  private OrderStatusTransitions() {}

  public static boolean isAllowed(OrderStatus from, OrderStatus to) {
    return ALLOWED.get(from).contains(to);
  }
}
