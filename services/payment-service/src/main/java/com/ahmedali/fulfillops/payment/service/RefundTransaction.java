package com.ahmedali.fulfillops.payment.service;

import com.ahmedali.fulfillops.payment.domain.IdempotencyRequest;
import com.ahmedali.fulfillops.payment.domain.IdempotencyRequestRepository;
import com.ahmedali.fulfillops.payment.domain.Payment;
import com.ahmedali.fulfillops.payment.domain.PaymentRepository;
import com.ahmedali.fulfillops.payment.domain.Refund;
import com.ahmedali.fulfillops.payment.domain.RefundRepository;
import com.ahmedali.fulfillops.payment.messaging.MoneyPayload;
import com.ahmedali.fulfillops.payment.messaging.OutboxEventWriter;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes exactly one refund attempt: refunds the payment's full original amount (never a
 * client-supplied one), flips the payment to REFUNDED, and writes the PaymentRefunded.v1 outbox
 * event, all atomically. refunds.payment_id is UNIQUE at the database level (see V2__payments.sql),
 * so a second concurrent attempt for the same payment loses this transaction's flush and
 * RefundService is what tells a legitimate replay apart from a genuine conflict.
 */
@Component
public class RefundTransaction {

  private static final String REFUNDED_EVENT_TYPE = "PaymentRefunded";
  private static final int EVENT_VERSION = 1;

  private final PaymentRepository paymentRepository;
  private final RefundRepository refundRepository;
  private final IdempotencyRequestRepository idempotencyRequestRepository;
  private final OutboxEventWriter outboxEventWriter;

  public RefundTransaction(
      PaymentRepository paymentRepository,
      RefundRepository refundRepository,
      IdempotencyRequestRepository idempotencyRequestRepository,
      OutboxEventWriter outboxEventWriter) {
    this.paymentRepository = paymentRepository;
    this.refundRepository = refundRepository;
    this.idempotencyRequestRepository = idempotencyRequestRepository;
    this.outboxEventWriter = outboxEventWriter;
  }

  @Transactional
  public Refund attempt(
      UUID paymentId,
      String actorId,
      String idempotencyKey,
      String reasonCode,
      UUID correlationId,
      String requestFingerprint) {
    Payment payment =
        paymentRepository
            .findById(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    if (!payment.isAuthorized()) {
      throw new InvalidRefundStateException(
          "payment "
              + paymentId
              + " is not refundable (current status: "
              + payment.getStatus()
              + ")");
    }

    UUID refundId = UUID.randomUUID();
    Refund refund =
        new Refund(
            refundId,
            paymentId,
            payment.getAmount(),
            payment.getCurrencyCode(),
            reasonCode,
            correlationId);
    refundRepository.save(refund);

    payment.markRefunded();
    paymentRepository.save(payment);

    idempotencyRequestRepository.save(
        new IdempotencyRequest(actorId, idempotencyKey, requestFingerprint, refundId));

    outboxEventWriter.write(
        REFUNDED_EVENT_TYPE,
        EVENT_VERSION,
        payment.getOrderId(),
        correlationId,
        null,
        new RefundedPayload(
            paymentId,
            MoneyPayload.of(payment.getCurrencyCode(), payment.getAmount()),
            reasonCode));

    return refund;
  }

  private record RefundedPayload(UUID paymentId, MoneyPayload amount, String reasonCode) {}
}
