package com.ahmedali.fulfillops.payment.service;

import com.ahmedali.fulfillops.payment.domain.PaymentAttempt;
import com.ahmedali.fulfillops.payment.domain.PaymentAttemptOutcome;
import com.ahmedali.fulfillops.payment.domain.PaymentAttemptRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists one payment_attempts row per attempt in its own, independent transaction (REQUIRES_NEW),
 * not the caller's. An attempt is audit history of what actually happened against the provider — it
 * must survive even when the surrounding authorization flow ultimately fails and its own
 * transaction rolls back (for example, a technical failure that propagates out of
 * InventoryReservedListener so Kafka's @RetryableTopic redelivers the message).
 */
@Component
public class PaymentAttemptRecorder {

  private final PaymentAttemptRepository paymentAttemptRepository;

  public PaymentAttemptRecorder(PaymentAttemptRepository paymentAttemptRepository) {
    this.paymentAttemptRepository = paymentAttemptRepository;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(
      UUID orderId, int attemptNumber, PaymentAttemptOutcome outcome, String detail) {
    paymentAttemptRepository.save(
        new PaymentAttempt(UUID.randomUUID(), orderId, attemptNumber, outcome, detail));
  }
}
