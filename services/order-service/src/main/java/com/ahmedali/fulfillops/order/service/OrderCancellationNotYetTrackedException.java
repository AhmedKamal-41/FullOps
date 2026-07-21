package com.ahmedali.fulfillops.order.service;

import java.util.UUID;

/**
 * Thrown when a compensation confirmation (InventoryReleased, PaymentRefunded, a fulfillment
 * cancellation) arrives before this service's own cancellation tracker row exists for the order —
 * possible because the event that starts the tracker (a customer/operator request, PaymentDeclined,
 * or FulfillmentStatusChanged) travels on a different topic with no ordering guarantee relative to
 * the confirmation. Deliberately not a NonRetryableEventProcessingException: @RetryableTopic gives
 * the tracker-creating event a later, less contended, chance to be processed first.
 */
public class OrderCancellationNotYetTrackedException extends RuntimeException {

  public OrderCancellationNotYetTrackedException(UUID orderId) {
    super("no cancellation is tracked yet for order " + orderId + " — retrying later");
  }
}
