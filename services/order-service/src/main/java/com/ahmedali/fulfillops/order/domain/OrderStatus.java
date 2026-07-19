package com.ahmedali.fulfillops.order.domain;

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
  CANCELLED,
  REQUIRES_REVIEW
}
