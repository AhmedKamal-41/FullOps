package com.ahmedali.fulfillops.order.service;

/**
 * A structurally valid request (passed Bean Validation) that's still not acceptable — e.g. mixed
 * currencies across line items. Mapped to HTTP 400.
 */
public class InvalidOrderRequestException extends RuntimeException {

  public InvalidOrderRequestException(String message) {
    super(message);
  }
}
