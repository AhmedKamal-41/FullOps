package com.ahmedali.fulfillops.payment.provider;

/**
 * A transient, technical failure calling the (simulated) provider — never a business decline. This
 * is the exception type PaymentAuthorizationClient's retry and circuit breaker react to; see its
 * two subtypes for the specific kinds of failure the simulator can produce.
 */
public abstract class ProviderUnavailableException extends RuntimeException {

  protected ProviderUnavailableException(String message) {
    super(message);
  }
}
