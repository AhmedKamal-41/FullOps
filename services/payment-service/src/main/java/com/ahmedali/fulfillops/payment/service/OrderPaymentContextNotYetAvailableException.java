package com.ahmedali.fulfillops.payment.service;

import java.util.UUID;

/**
 * order-service's OrderPlaced.v1 for this order hasn't been consumed yet, so Payment Service does
 * not yet know the customer/amount/currency needed to authorize. This is a transient, not a
 * business, condition — order-service and inventory-service's consumers may finish in either order
 * — so it is deliberately not a NonRetryableEventProcessingException: letting it propagate out of
 * InventoryReservedListener lets Kafka's @RetryableTopic redeliver the message, giving
 * OrderPlacedListener time to catch up.
 */
public class OrderPaymentContextNotYetAvailableException extends RuntimeException {

  public OrderPaymentContextNotYetAvailableException(UUID orderId) {
    super("no order payment context yet for order " + orderId);
  }
}
