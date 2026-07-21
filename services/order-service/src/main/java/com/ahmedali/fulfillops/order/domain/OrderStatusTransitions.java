package com.ahmedali.fulfillops.order.domain;

import java.util.Map;
import java.util.Set;

/**
 * The allowed-transitions table from docs/DOMAIN_MODEL.md's order status state machine, made
 * explicit and checkable in code. CANCELLED is reachable directly only from PENDING — the one case
 * where nothing was ever reserved or charged, so there is nothing to wait for (see
 * OrderCancellationTransaction). Every other nonterminal status routes cancellation through
 * CANCELLATION_PENDING first, since at least one compensation (inventory release, payment refund,
 * fulfillment cancellation) must be confirmed before the order can actually be CANCELLED. Every
 * status ReconciliationService's HAPPY_PATH_NONTERMINAL_STATUSES watches (PENDING through
 * DISPATCHED) also allows a direct move to REQUIRES_REVIEW, since reconciliation must be able to
 * escalate an order stuck in any of them.
 */
public final class OrderStatusTransitions {

  private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED =
      Map.ofEntries(
          Map.entry(
              OrderStatus.PENDING,
              Set.of(
                  OrderStatus.INVENTORY_RESERVED,
                  OrderStatus.CANCELLED,
                  OrderStatus.REQUIRES_REVIEW)),
          Map.entry(
              OrderStatus.INVENTORY_RESERVED,
              Set.of(
                  OrderStatus.PAYMENT_AUTHORIZED,
                  OrderStatus.CANCELLATION_PENDING,
                  OrderStatus.REQUIRES_REVIEW)),
          Map.entry(
              OrderStatus.PAYMENT_AUTHORIZED,
              Set.of(
                  OrderStatus.FULFILLMENT_ASSIGNED,
                  OrderStatus.CANCELLATION_PENDING,
                  OrderStatus.REQUIRES_REVIEW)),
          Map.entry(
              OrderStatus.FULFILLMENT_ASSIGNED,
              Set.of(
                  OrderStatus.PICKING,
                  OrderStatus.CANCELLATION_PENDING,
                  OrderStatus.REQUIRES_REVIEW)),
          Map.entry(
              OrderStatus.PICKING,
              Set.of(
                  OrderStatus.PACKED,
                  OrderStatus.CANCELLATION_PENDING,
                  OrderStatus.REQUIRES_REVIEW)),
          Map.entry(
              OrderStatus.PACKED,
              Set.of(
                  OrderStatus.DISPATCHED,
                  OrderStatus.CANCELLATION_PENDING,
                  OrderStatus.REQUIRES_REVIEW)),
          Map.entry(
              OrderStatus.DISPATCHED, Set.of(OrderStatus.DELIVERED, OrderStatus.REQUIRES_REVIEW)),
          Map.entry(OrderStatus.DELIVERED, Set.of()),
          Map.entry(
              OrderStatus.CANCELLATION_PENDING,
              Set.of(OrderStatus.CANCELLED, OrderStatus.REQUIRES_REVIEW)),
          Map.entry(OrderStatus.CANCELLED, Set.of()),
          Map.entry(OrderStatus.REQUIRES_REVIEW, Set.of(OrderStatus.CANCELLED)));

  private OrderStatusTransitions() {}

  public static boolean isAllowed(OrderStatus from, OrderStatus to) {
    return ALLOWED.get(from).contains(to);
  }
}
