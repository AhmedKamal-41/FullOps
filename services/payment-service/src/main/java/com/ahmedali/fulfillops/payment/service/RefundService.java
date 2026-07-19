package com.ahmedali.fulfillops.payment.service;

import com.ahmedali.fulfillops.payment.domain.IdempotencyRequest;
import com.ahmedali.fulfillops.payment.domain.IdempotencyRequestId;
import com.ahmedali.fulfillops.payment.domain.IdempotencyRequestRepository;
import com.ahmedali.fulfillops.payment.domain.Payment;
import com.ahmedali.fulfillops.payment.domain.PaymentRepository;
import com.ahmedali.fulfillops.payment.domain.Refund;
import com.ahmedali.fulfillops.payment.domain.RefundReasonCode;
import com.ahmedali.fulfillops.payment.domain.RefundRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a refund request: an idempotency-key replay check (same shape as inventory-service's
 * StockAdjustmentService), then one RefundTransaction attempt. A refund is idempotent in two senses
 * at once: the Idempotency-Key protects a single caller against its own retried request, and
 * refunds.payment_id being UNIQUE protects against two different callers (or two different keys)
 * both trying to refund the same payment.
 */
@Service
public class RefundService {

  private final IdempotencyRequestRepository idempotencyRequestRepository;
  private final RefundRepository refundRepository;
  private final PaymentRepository paymentRepository;
  private final RefundTransaction refundTransaction;

  public RefundService(
      IdempotencyRequestRepository idempotencyRequestRepository,
      RefundRepository refundRepository,
      PaymentRepository paymentRepository,
      RefundTransaction refundTransaction) {
    this.idempotencyRequestRepository = idempotencyRequestRepository;
    this.refundRepository = refundRepository;
    this.paymentRepository = paymentRepository;
    this.refundTransaction = refundTransaction;
  }

  public Refund refund(
      String actorId,
      String idempotencyKey,
      UUID paymentId,
      RefundReasonCode reasonCode,
      UUID correlationId) {
    String fingerprint = fingerprint(actorId, paymentId, reasonCode);
    IdempotencyRequestId idempotencyRequestId = new IdempotencyRequestId(actorId, idempotencyKey);

    Optional<IdempotencyRequest> existing =
        idempotencyRequestRepository.findById(idempotencyRequestId);
    if (existing.isPresent()) {
      return replayOrConflict(existing.get(), idempotencyKey, fingerprint);
    }

    try {
      return refundTransaction.attempt(
          paymentId, actorId, idempotencyKey, reasonCode.name(), correlationId, fingerprint);
    } catch (DataIntegrityViolationException lostRace) {
      return resolveAfterLostRace(
          idempotencyRequestId, idempotencyKey, fingerprint, paymentId, lostRace);
    }
  }

  private Refund resolveAfterLostRace(
      IdempotencyRequestId idempotencyRequestId,
      String idempotencyKey,
      String fingerprint,
      UUID paymentId,
      DataIntegrityViolationException lostRace) {
    Optional<IdempotencyRequest> winner =
        idempotencyRequestRepository.findById(idempotencyRequestId);
    if (winner.isPresent()) {
      return replayOrConflict(winner.get(), idempotencyKey, fingerprint);
    }

    // Not an Idempotency-Key race: a different actor or a different key raced this one for the
    // same payment and won refunds.payment_id's unique constraint first.
    Payment payment = paymentRepository.findById(paymentId).orElseThrow(() -> lostRace);
    if (!payment.isAuthorized()) {
      throw new InvalidRefundStateException(
          "payment "
              + paymentId
              + " is not refundable (current status: "
              + payment.getStatus()
              + ")");
    }
    throw lostRace;
  }

  private Refund replayOrConflict(
      IdempotencyRequest existing, String idempotencyKey, String fingerprint) {
    if (!existing.getRequestFingerprint().equals(fingerprint)) {
      throw new IdempotencyKeyConflictException(idempotencyKey);
    }
    return refundRepository
        .findById(existing.getRefundId())
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "idempotency_requests row references a missing refund: "
                        + existing.getRefundId()));
  }

  private static String fingerprint(String actorId, UUID paymentId, RefundReasonCode reasonCode) {
    return RequestFingerprint.sha256Hex(actorId + '|' + paymentId + '|' + reasonCode);
  }
}
