package com.ahmedali.fulfillops.fulfillment.messaging;

/**
 * Thrown when redelivering and reprocessing an event would produce the same rejection every time —
 * a business rule violation, not a transient infrastructure problem. Listed in
 * InboxEventListener's @RetryableTopic(exclude = ...) so these skip retry entirely and go straight
 * to the dead-letter topic instead of being retried pointlessly.
 */
public class NonRetryableEventProcessingException extends RuntimeException {

  public NonRetryableEventProcessingException(String message) {
    super(message);
  }
}
