package com.ahmedali.fulfillops.payment.resilience;

import com.ahmedali.fulfillops.payment.domain.PaymentAttemptOutcome;

/**
 * Called by PaymentAuthorizationClient once per raw attempt against the provider, whether it
 * succeeded, was declined, failed technically, or was rejected by an open circuit breaker before
 * the provider was even called. AuthorizationService supplies the implementation that persists each
 * attempt via PaymentAttemptRecorder.
 */
@FunctionalInterface
public interface PaymentAttemptListener {

  void onAttempt(int attemptNumber, PaymentAttemptOutcome outcome, String detail);
}
