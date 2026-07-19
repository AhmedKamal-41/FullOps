package com.ahmedali.fulfillops.inventory.service;

/**
 * The same Idempotency-Key was reused with a materially different request body. Mapped to HTTP 409
 * — a real conflict, not a retry-safe replay.
 */
public class IdempotencyKeyConflictException extends RuntimeException {

  public IdempotencyKeyConflictException(String idempotencyKey) {
    super("Idempotency-Key '" + idempotencyKey + "' was already used with a different request");
  }
}
