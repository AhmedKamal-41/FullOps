package com.ahmedali.fulfillops.payment.service;

/** A structurally valid refund request with an unknown reasonCode. Mapped to HTTP 400. */
public class InvalidRefundRequestException extends RuntimeException {

  public InvalidRefundRequestException(String message) {
    super(message);
  }
}
