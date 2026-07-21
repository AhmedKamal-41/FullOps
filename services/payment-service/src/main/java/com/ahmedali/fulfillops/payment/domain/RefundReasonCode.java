package com.ahmedali.fulfillops.payment.domain;

/** Matches PaymentRefunded.v1's closed reasonCode enum in contracts/events/. */
public enum RefundReasonCode {
  FULFILLMENT_CANCELLED,
  ORDER_CANCELLED
}
