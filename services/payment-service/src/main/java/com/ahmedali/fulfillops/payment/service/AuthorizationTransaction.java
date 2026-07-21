package com.ahmedali.fulfillops.payment.service;

import com.ahmedali.fulfillops.payment.domain.Payment;
import com.ahmedali.fulfillops.payment.domain.PaymentRepository;
import com.ahmedali.fulfillops.payment.messaging.MoneyPayload;
import com.ahmedali.fulfillops.payment.messaging.OutboxEventWriter;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the final payment outcome and its outbox event together, atomically — either both commit
 * or neither does. Called at most once per order by AuthorizationService, after
 * PaymentAuthorizationClient has finished retrying and produced a final business outcome (not a
 * technical failure, which never reaches this class).
 */
@Component
public class AuthorizationTransaction {

  private static final String AUTHORIZED_EVENT_TYPE = "PaymentAuthorized";
  private static final String DECLINED_EVENT_TYPE = "PaymentDeclined";
  private static final int EVENT_VERSION = 1;

  private final PaymentRepository paymentRepository;
  private final OutboxEventWriter outboxEventWriter;

  public AuthorizationTransaction(
      PaymentRepository paymentRepository, OutboxEventWriter outboxEventWriter) {
    this.paymentRepository = paymentRepository;
    this.outboxEventWriter = outboxEventWriter;
  }

  @Transactional
  public Payment recordApproved(
      UUID orderId,
      UUID customerId,
      BigDecimal amount,
      String currencyCode,
      int precedingTechnicalFailureCount,
      UUID correlationId,
      UUID causationId) {
    UUID paymentId = UUID.randomUUID();
    Payment payment =
        Payment.authorized(paymentId, orderId, customerId, amount, currencyCode, correlationId);
    paymentRepository.save(payment);

    outboxEventWriter.write(
        AUTHORIZED_EVENT_TYPE,
        EVENT_VERSION,
        orderId,
        correlationId,
        causationId,
        new AuthorizedPayload(
            paymentId, MoneyPayload.of(currencyCode, amount), precedingTechnicalFailureCount));
    return payment;
  }

  @Transactional
  public Payment recordDeclined(
      UUID orderId,
      UUID customerId,
      BigDecimal amount,
      String currencyCode,
      String reasonCode,
      String reasonDetail,
      int precedingTechnicalFailureCount,
      UUID correlationId,
      UUID causationId) {
    UUID paymentId = UUID.randomUUID();
    Payment payment =
        Payment.declined(
            paymentId,
            orderId,
            customerId,
            amount,
            currencyCode,
            reasonCode,
            reasonDetail,
            correlationId);
    paymentRepository.save(payment);

    outboxEventWriter.write(
        DECLINED_EVENT_TYPE,
        EVENT_VERSION,
        orderId,
        correlationId,
        causationId,
        new DeclinedPayload(
            MoneyPayload.of(currencyCode, amount),
            reasonCode,
            reasonDetail,
            precedingTechnicalFailureCount));
    return payment;
  }

  private record AuthorizedPayload(
      UUID paymentId, MoneyPayload amount, int precedingTechnicalFailureCount) {}

  private record DeclinedPayload(
      MoneyPayload amount,
      String reasonCode,
      String reasonDetail,
      int precedingTechnicalFailureCount) {}
}
