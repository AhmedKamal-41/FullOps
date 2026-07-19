package com.ahmedali.fulfillops.payment.service;

/**
 * A refund was requested for a payment that isn't in a refundable state — never authorized (e.g.
 * DECLINED) or already refunded by a different request. Mapped to HTTP 409: it's a real conflict,
 * not something a retry of the same request will resolve.
 */
public class InvalidRefundStateException extends RuntimeException {

  public InvalidRefundStateException(String message) {
    super(message);
  }
}
