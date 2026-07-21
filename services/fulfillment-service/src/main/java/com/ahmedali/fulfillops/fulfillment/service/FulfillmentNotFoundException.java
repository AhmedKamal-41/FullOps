package com.ahmedali.fulfillops.fulfillment.service;

import java.util.UUID;

public class FulfillmentNotFoundException extends RuntimeException {

  public FulfillmentNotFoundException(UUID fulfillmentId) {
    super("no fulfillment found with id " + fulfillmentId);
  }
}
