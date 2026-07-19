package com.ahmedali.fulfillops.order.web;

import java.util.UUID;

/**
 * Also thrown when the order exists but the requester isn't its owner or staff — see
 * OrderController.getOrder. Returning 404 instead of 403 for that case avoids confirming to an
 * unauthorized caller that the order exists at all.
 */
public class OrderNotFoundException extends RuntimeException {

  public OrderNotFoundException(UUID orderId) {
    super("No order found with id " + orderId);
  }
}
