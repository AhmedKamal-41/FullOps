package com.ahmedali.fulfillops.order.domain;

/**
 * Matches OrderCancelled.v1's full reasonCode enum in contracts/events/. A superset of
 * OrderCancellationReasonCode: INVENTORY_REJECTED never has an OrderCancellation tracker row (an
 * inventory rejection cancels immediately, since nothing was ever reserved or charged), but it's
 * still a valid final reason the terminal OrderCancelled.v1 event can carry.
 */
public enum OrderCancelledReasonCode {
  INVENTORY_REJECTED,
  PAYMENT_DECLINED,
  FULFILLMENT_CANCELLED,
  CUSTOMER_REQUESTED,
  OPERATOR_REQUESTED
}
