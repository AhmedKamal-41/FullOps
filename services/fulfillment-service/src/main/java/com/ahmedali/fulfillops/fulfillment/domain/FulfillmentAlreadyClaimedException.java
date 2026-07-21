package com.ahmedali.fulfillops.fulfillment.domain;

import java.util.UUID;

/** Thrown when an operator tries to claim a fulfillment that already has an assignee. */
public class FulfillmentAlreadyClaimedException extends RuntimeException {

  public FulfillmentAlreadyClaimedException(UUID fulfillmentId, String currentAssigneeId) {
    super("fulfillment " + fulfillmentId + " is already claimed by " + currentAssigneeId);
  }
}
