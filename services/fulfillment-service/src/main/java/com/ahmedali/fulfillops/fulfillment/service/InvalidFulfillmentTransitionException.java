package com.ahmedali.fulfillops.fulfillment.service;

import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentStatus;

public class InvalidFulfillmentTransitionException extends RuntimeException {

  public InvalidFulfillmentTransitionException(FulfillmentStatus from, FulfillmentStatus to) {
    super("cannot move a fulfillment from " + from + " to " + to);
  }
}
