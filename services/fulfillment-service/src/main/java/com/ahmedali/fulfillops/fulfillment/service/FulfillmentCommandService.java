package com.ahmedali.fulfillops.fulfillment.service;

import com.ahmedali.fulfillops.fulfillment.domain.Fulfillment;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Thin orchestration layer between FulfillmentController and FulfillmentTransition: parses/
 * validates request fields that depend on which target status was requested, then delegates the
 * actual state change. A genuine concurrent-write race surfaces from FulfillmentTransition as
 * Hibernate's own OptimisticLockingFailureException — caught here and translated into the same
 * FulfillmentVersionConflictException an explicit stale If-Match header produces, so the controller
 * only ever has one conflict type to map to a 409.
 */
@Service
public class FulfillmentCommandService {

  private final FulfillmentTransition fulfillmentTransition;

  public FulfillmentCommandService(FulfillmentTransition fulfillmentTransition) {
    this.fulfillmentTransition = fulfillmentTransition;
  }

  public Fulfillment claim(UUID fulfillmentId, long expectedVersion, String actorId) {
    try {
      return fulfillmentTransition.claim(fulfillmentId, expectedVersion, actorId);
    } catch (OptimisticLockingFailureException concurrentWrite) {
      throw new FulfillmentVersionConflictException(fulfillmentId);
    }
  }

  public Fulfillment advance(
      UUID fulfillmentId,
      long expectedVersion,
      String requestedStatus,
      String trackingReference,
      Instant deliveredAt,
      String notes,
      String actorId,
      UUID correlationId) {
    FulfillmentStatus newStatus = parseAdvanceableStatus(requestedStatus);
    if (newStatus == FulfillmentStatus.DISPATCHED && isBlank(trackingReference)) {
      throw new InvalidFulfillmentRequestException(
          "trackingReference is required to mark a fulfillment DISPATCHED");
    }
    if (newStatus == FulfillmentStatus.DELIVERED && deliveredAt == null) {
      throw new InvalidFulfillmentRequestException(
          "deliveredAt is required to mark a fulfillment DELIVERED");
    }

    try {
      return fulfillmentTransition.advance(
          fulfillmentId,
          expectedVersion,
          newStatus,
          trackingReference,
          deliveredAt,
          notes,
          actorId,
          correlationId);
    } catch (OptimisticLockingFailureException concurrentWrite) {
      throw new FulfillmentVersionConflictException(fulfillmentId);
    }
  }

  public Fulfillment cancel(
      UUID fulfillmentId,
      long expectedVersion,
      String reasonDetail,
      String actorId,
      UUID correlationId) {
    try {
      return fulfillmentTransition.cancel(
          fulfillmentId, expectedVersion, reasonDetail, actorId, correlationId);
    } catch (OptimisticLockingFailureException concurrentWrite) {
      throw new FulfillmentVersionConflictException(fulfillmentId);
    }
  }

  private static FulfillmentStatus parseAdvanceableStatus(String requestedStatus) {
    FulfillmentStatus status;
    try {
      status = FulfillmentStatus.valueOf(requestedStatus);
    } catch (IllegalArgumentException notAKnownStatus) {
      throw new InvalidFulfillmentRequestException("unknown newStatus: " + requestedStatus);
    }
    if (status == FulfillmentStatus.ASSIGNED || status == FulfillmentStatus.CANCELLED) {
      throw new InvalidFulfillmentRequestException(
          "newStatus "
              + status
              + " is not reachable through this endpoint; use POST .../cancel to cancel");
    }
    return status;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
