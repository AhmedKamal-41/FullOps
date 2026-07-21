package com.ahmedali.fulfillops.order.service;

import com.ahmedali.fulfillops.order.domain.OrderStatus;
import java.util.UUID;

/**
 * Thrown when an event implies a status transition Order Service isn't ready to apply yet because
 * an earlier milestone for this order hasn't been processed — for example, PaymentAuthorized
 * arriving while the order is still PENDING (InventoryReserved not yet processed). Different
 * producers' topics carry no cross-topic ordering guarantee, so this is expected under normal
 * operation, not a bug. Deliberately not a NonRetryableEventProcessingException: @RetryableTopic
 * gives the missing predecessor event time to be processed first.
 */
public class OrderMilestoneTooEarlyException extends RuntimeException {

  public OrderMilestoneTooEarlyException(UUID orderId, OrderStatus currentStatus) {
    super("order " + orderId + " is still " + currentStatus + " — retrying later");
  }
}
