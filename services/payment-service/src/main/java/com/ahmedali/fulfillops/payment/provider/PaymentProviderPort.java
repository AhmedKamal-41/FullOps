package com.ahmedali.fulfillops.payment.provider;

/**
 * The boundary between payment-authorization business logic and whatever actually decides an
 * outcome. The only production implementation is SimulatorPaymentProviderAdapter (a deterministic,
 * fictional simulator — this project never calls a real payment network); tests use a fake
 * implementation instead, so PaymentAuthorizationClient's retry/circuit-breaker behavior can be
 * proven without depending on HTTP or randomness.
 */
public interface PaymentProviderPort {

  ProviderAuthorizationOutcome authorize(ProviderAuthorizationRequest request);
}
