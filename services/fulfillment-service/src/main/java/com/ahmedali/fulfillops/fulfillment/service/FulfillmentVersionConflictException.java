package com.ahmedali.fulfillops.fulfillment.service;

import java.util.UUID;

/**
 * Thrown when a command's If-Match version doesn't match the fulfillment's current version — either
 * because the caller's copy is stale (a prior request already moved it forward) or because a
 * concurrent request won the race first. Either way, the right response is the same: refetch and
 * retry, not silently overwrite someone else's work.
 */
public class FulfillmentVersionConflictException extends RuntimeException {

  public FulfillmentVersionConflictException(
      UUID fulfillmentId, long expectedVersion, long actualVersion) {
    super(
        "fulfillment "
            + fulfillmentId
            + " has version "
            + actualVersion
            + ", but the request's If-Match header supplied version "
            + expectedVersion
            + " — refresh and retry");
  }

  public FulfillmentVersionConflictException(UUID fulfillmentId) {
    super("fulfillment " + fulfillmentId + " was modified concurrently — refresh and retry");
  }
}
