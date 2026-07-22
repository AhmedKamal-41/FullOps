package com.ahmedali.fulfillops.fulfillment.service;

import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentStatus;
import java.util.UUID;

/**
 * Thrown when an operator tries to cancel a fulfillment that has already been dispatched or
 * delivered — goods are physically in transit (or already handed over) at that point, so
 * cancellation is never automatic. reasonCode is machine-readable on purpose: the
 * REQUIRES_REVIEW routing (see docs/ARCHITECTURE.md's compensation rules) can key off it instead of
 * parsing free text.
 */
public class FulfillmentCancellationNotAllowedException extends RuntimeException {

  private static final String REASON_CODE = "CANCELLATION_NOT_ALLOWED_AFTER_DISPATCH";

  private final UUID fulfillmentId;

  public FulfillmentCancellationNotAllowedException(UUID fulfillmentId, FulfillmentStatus status) {
    super("fulfillment " + fulfillmentId + " cannot be cancelled once it has reached " + status);
    this.fulfillmentId = fulfillmentId;
  }

  public UUID getFulfillmentId() {
    return fulfillmentId;
  }

  public String getReasonCode() {
    return REASON_CODE;
  }
}
