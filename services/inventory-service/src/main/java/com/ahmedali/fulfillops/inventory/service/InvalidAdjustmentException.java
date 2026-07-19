package com.ahmedali.fulfillops.inventory.service;

/**
 * A structurally valid adjustment request that's still not acceptable — e.g. unknown reasonCode, a
 * zero changeQuantity, or one that would drive available stock negative. Mapped to HTTP 400.
 */
public class InvalidAdjustmentException extends RuntimeException {

  public InvalidAdjustmentException(String message) {
    super(message);
  }
}
