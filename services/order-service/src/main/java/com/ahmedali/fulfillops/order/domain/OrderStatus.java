package com.ahmedali.fulfillops.order.domain;

import java.util.List;

/** Matches the orders.status and order_status_history.status CHECK constraints. */
public enum OrderStatus {
  PENDING,
  INVENTORY_RESERVED,
  PAYMENT_AUTHORIZED,
  FULFILLMENT_ASSIGNED,
  PICKING,
  PACKED,
  DISPATCHED,
  DELIVERED,
  CANCELLATION_PENDING,
  CANCELLED,
  REQUIRES_REVIEW;

  /**
   * Every non-terminal status with normal-flow SLA meaning — used by the ops backlog/work-queue/
   * stuck-orders KPIs (StageDurationKpiService, WorkQueueService). REQUIRES_REVIEW is deliberately
   * excluded: an order lands there because something needed a human decision, not because of
   * normal-flow stage duration, and it's already surfaced through the incident queue instead.
   */
  public static final List<OrderStatus> OPEN_STAGES =
      List.of(
          PENDING,
          INVENTORY_RESERVED,
          PAYMENT_AUTHORIZED,
          FULFILLMENT_ASSIGNED,
          PICKING,
          PACKED,
          DISPATCHED,
          CANCELLATION_PENDING);
}
