package com.ahmedali.fulfillops.payment.provider;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Everything the (simulated) provider needs to decide an outcome. attemptNumber is what lets
 * SimulatorPaymentProviderAdapter apply a rule's failingAttempts deterministically, and continues
 * counting across Kafka redeliveries — see AuthorizationService.
 */
public record ProviderAuthorizationRequest(
    UUID orderId, UUID customerId, BigDecimal amount, String currencyCode, int attemptNumber) {

  public ProviderAuthorizationRequest withAttemptNumber(int newAttemptNumber) {
    return new ProviderAuthorizationRequest(
        orderId, customerId, amount, currencyCode, newAttemptNumber);
  }
}
