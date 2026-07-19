package com.ahmedali.fulfillops.inventory.service;

import java.util.UUID;

/** Thrown when a release is requested for an order Inventory never reserved stock for. */
public class ReservationNotFoundException extends RuntimeException {

  public ReservationNotFoundException(UUID orderId) {
    super("no inventory reservation exists for order " + orderId);
  }
}
