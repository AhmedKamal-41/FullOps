package com.ahmedali.fulfillops.payment.domain;

/**
 * What happened on one attempt against the (simulated) provider. APPROVED/DECLINED are business
 * outcomes; TIMEOUT/TEMPORARY_ERROR are transient technical failures eligible for retry;
 * CIRCUIT_OPEN means the circuit breaker rejected the call before the provider was even invoked.
 */
public enum PaymentAttemptOutcome {
  APPROVED,
  DECLINED,
  TIMEOUT,
  TEMPORARY_ERROR,
  CIRCUIT_OPEN
}
