package com.ahmedali.fulfillops.order.domain;

/**
 * Matches OrderCancelled.v1's reasonCode enum in contracts/events/, minus INVENTORY_REJECTED — an
 * inventory rejection cancels an order directly (nothing was ever reserved or charged, so there is
 * no OrderCancellation row to track), while every code here corresponds to a real compensation saga
 * tracked by an OrderCancellation row.
 */
public enum OrderCancellationReasonCode {
  PAYMENT_DECLINED,
  FULFILLMENT_CANCELLED,
  CUSTOMER_REQUESTED,
  OPERATOR_REQUESTED
}
