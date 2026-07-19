package com.ahmedali.fulfillops.payment.service;

import java.util.UUID;

/**
 * Thrown when a caller asks for a payment (or its attempts, or a refund of it) that doesn't exist.
 */
public class PaymentNotFoundException extends RuntimeException {

  public PaymentNotFoundException(UUID paymentId) {
    super("no payment exists with id " + paymentId);
  }
}
