package com.ahmedali.fulfillops.fulfillment.service;

import com.ahmedali.fulfillops.fulfillment.domain.Fulfillment;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentRepository;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentStatus;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentStatusHistory;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentStatusHistoryRepository;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentStatusTransitions;
import com.ahmedali.fulfillops.fulfillment.messaging.OutboxEventWriter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes exactly one command attempt against a fulfillment — claim, advance, or cancel — with its
 * state change, history row, and outbox event (where applicable) committed atomically. Each method
 * checks the caller's expected version against the fulfillment's current version before mutating
 * anything: a mismatch here is an immediate, precise rejection; a genuine race between two
 * concurrently-loaded copies is instead caught by Hibernate's own @Version check at save time and
 * surfaces as OptimisticLockingFailureException, which FulfillmentCommandService translates into
 * the same conflict response.
 */
@Component
public class FulfillmentTransition {

  private static final Logger log = LoggerFactory.getLogger(FulfillmentTransition.class);
  private static final String STATUS_CHANGED_EVENT_TYPE = "FulfillmentStatusChanged";
  private static final int EVENT_VERSION = 1;
  private static final String OPERATOR_CANCELLED_REASON_CODE = "OPERATOR_CANCELLED";
  private static final String ORDER_CANCELLATION_REQUESTED_REASON_CODE =
      "ORDER_CANCELLATION_REQUESTED";

  private final FulfillmentRepository fulfillmentRepository;
  private final FulfillmentStatusHistoryRepository statusHistoryRepository;
  private final OutboxEventWriter outboxEventWriter;
  private final MeterRegistry meterRegistry;

  public FulfillmentTransition(
      FulfillmentRepository fulfillmentRepository,
      FulfillmentStatusHistoryRepository statusHistoryRepository,
      OutboxEventWriter outboxEventWriter,
      MeterRegistry meterRegistry) {
    this.fulfillmentRepository = fulfillmentRepository;
    this.statusHistoryRepository = statusHistoryRepository;
    this.outboxEventWriter = outboxEventWriter;
    this.meterRegistry = meterRegistry;
  }

  @Transactional
  public Fulfillment claim(UUID fulfillmentId, long expectedVersion, String actorId) {
    Fulfillment fulfillment = load(fulfillmentId);
    requireCurrentVersion(fulfillment, expectedVersion);

    FulfillmentStatus previousStatus = fulfillment.getStatus();
    fulfillment.claim(actorId);
    Fulfillment saved = fulfillmentRepository.save(fulfillment);
    recordTransition(previousStatus, saved.getStatus());
    return saved;
  }

  @Transactional
  public Fulfillment advance(
      UUID fulfillmentId,
      long expectedVersion,
      FulfillmentStatus newStatus,
      String trackingReference,
      Instant deliveredAt,
      String notes,
      String actorId,
      UUID correlationId) {
    Fulfillment fulfillment = load(fulfillmentId);
    requireCurrentVersion(fulfillment, expectedVersion);

    FulfillmentStatus previousStatus = fulfillment.getStatus();
    if (!FulfillmentStatusTransitions.isAllowedAdvance(previousStatus, newStatus)) {
      throw new InvalidFulfillmentTransitionException(previousStatus, newStatus);
    }

    fulfillment.advanceTo(newStatus, trackingReference, deliveredAt);
    fulfillmentRepository.save(fulfillment);

    statusHistoryRepository.save(
        new FulfillmentStatusHistory(fulfillmentId, newStatus, actorId, notes));

    outboxEventWriter.write(
        STATUS_CHANGED_EVENT_TYPE,
        EVENT_VERSION,
        fulfillment.getOrderId(),
        correlationId,
        null,
        new AdvancedPayload(fulfillmentId, previousStatus.name(), newStatus.name()));
    recordTransition(previousStatus, newStatus);

    return fulfillment;
  }

  @Transactional
  public Fulfillment cancel(
      UUID fulfillmentId,
      long expectedVersion,
      String reasonDetail,
      String actorId,
      UUID correlationId) {
    Fulfillment fulfillment = load(fulfillmentId);
    if (fulfillment.getStatus() == FulfillmentStatus.CANCELLED) {
      // A repeated cancellation request (e.g. a caller retrying after a dropped response) must be
      // a no-op, not a stale-version conflict — the fulfillment is already exactly where the
      // caller wants it, regardless of what version they think it's at.
      return fulfillment;
    }
    if (!FulfillmentStatusTransitions.isCancellable(fulfillment.getStatus())) {
      throw new FulfillmentCancellationNotAllowedException(fulfillmentId, fulfillment.getStatus());
    }
    requireCurrentVersion(fulfillment, expectedVersion);

    return performCancel(
        fulfillment, reasonDetail, actorId, correlationId, OPERATOR_CANCELLED_REASON_CODE);
  }

  /**
   * The event-driven counterpart to cancel(): reacts to Order Service's
   * OrderCancellationRequested.v1 rather than a direct HTTP call, so there is no caller-supplied
   * version to check (this transaction's own fresh read is authoritative) and no caller waiting for
   * an HTTP error if cancellation turns out not to be possible. If there's no fulfillment for this
   * order yet, it's already CANCELLED, or it's no longer cancellable (already
   * DISPATCHED/DELIVERED), this is a silent no-op — Order Service's reconciliation is the safety
   * net for a confirmation that never arrives.
   */
  @Transactional
  public Optional<Fulfillment> cancelForOrder(
      UUID orderId, String reasonDetail, UUID correlationId) {
    Optional<Fulfillment> existing = fulfillmentRepository.findByOrderId(orderId);
    if (existing.isEmpty()) {
      log.info("no fulfillment exists yet for order {}, nothing to cancel", orderId);
      return Optional.empty();
    }

    Fulfillment fulfillment = existing.get();
    if (fulfillment.getStatus() == FulfillmentStatus.CANCELLED) {
      return Optional.of(fulfillment);
    }
    if (!FulfillmentStatusTransitions.isCancellable(fulfillment.getStatus())) {
      log.info(
          "fulfillment {} for order {} is already {} and can no longer be cancelled",
          fulfillment.getFulfillmentId(),
          orderId,
          fulfillment.getStatus());
      return Optional.empty();
    }

    return Optional.of(
        performCancel(
            fulfillment,
            reasonDetail,
            "system",
            correlationId,
            ORDER_CANCELLATION_REQUESTED_REASON_CODE));
  }

  private Fulfillment performCancel(
      Fulfillment fulfillment,
      String reasonDetail,
      String actorId,
      UUID correlationId,
      String reasonCode) {
    UUID fulfillmentId = fulfillment.getFulfillmentId();
    FulfillmentStatus previousStatus = fulfillment.getStatus();
    fulfillment.cancel(reasonCode, reasonDetail);
    fulfillmentRepository.save(fulfillment);

    statusHistoryRepository.save(
        new FulfillmentStatusHistory(
            fulfillmentId, FulfillmentStatus.CANCELLED, actorId, reasonDetail));

    outboxEventWriter.write(
        STATUS_CHANGED_EVENT_TYPE,
        EVENT_VERSION,
        fulfillment.getOrderId(),
        correlationId,
        null,
        new CancelledPayload(
            fulfillmentId,
            previousStatus.name(),
            FulfillmentStatus.CANCELLED.name(),
            reasonCode,
            reasonDetail));
    recordTransition(previousStatus, FulfillmentStatus.CANCELLED);

    return fulfillment;
  }

  private void recordTransition(FulfillmentStatus fromStatus, FulfillmentStatus toStatus) {
    meterRegistry
        .counter(
            "fulfillment.stage.transition",
            "fromStage",
            fromStatus.name(),
            "toStage",
            toStatus.name())
        .increment();
  }

  private Fulfillment load(UUID fulfillmentId) {
    return fulfillmentRepository
        .findById(fulfillmentId)
        .orElseThrow(() -> new FulfillmentNotFoundException(fulfillmentId));
  }

  private void requireCurrentVersion(Fulfillment fulfillment, long expectedVersion) {
    if (fulfillment.getVersion() != expectedVersion) {
      throw new FulfillmentVersionConflictException(
          fulfillment.getFulfillmentId(), expectedVersion, fulfillment.getVersion());
    }
  }

  private record AdvancedPayload(UUID fulfillmentId, String previousStatus, String newStatus) {}

  private record CancelledPayload(
      UUID fulfillmentId,
      String previousStatus,
      String newStatus,
      String reasonCode,
      String reasonDetail) {}
}
