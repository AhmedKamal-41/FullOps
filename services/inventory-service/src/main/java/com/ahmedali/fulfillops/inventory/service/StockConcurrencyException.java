package com.ahmedali.fulfillops.inventory.service;

/**
 * Thrown when every bounded in-process retry attempt lost the optimistic-lock race for the same
 * stock_level or inventory_reservation row — reservation, release, and admin adjustment all share
 * this. It is a transient-infrastructure-shaped failure, not a business rejection, so it is allowed
 * to propagate out of OrderPlacedListener and let the existing @RetryableTopic Kafka-level retry
 * (with backoff) get a later, less contended, chance; a REST caller sees it mapped to HTTP 409.
 */
public class StockConcurrencyException extends RuntimeException {

  public StockConcurrencyException(String subject, int attempts, Throwable cause) {
    super(
        "could not update stock for "
            + subject
            + " after "
            + attempts
            + " attempts due to concurrent updates",
        cause);
  }
}
