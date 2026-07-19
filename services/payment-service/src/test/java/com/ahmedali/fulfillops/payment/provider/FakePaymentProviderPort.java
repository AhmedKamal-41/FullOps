package com.ahmedali.fulfillops.payment.provider;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

/**
 * A scripted PaymentProviderPort test double: each call to authorize() consumes the next scripted
 * response. This is the "fake provider port" the phase charter asks for — it lets
 * PaymentAuthorizationClientTest prove retry/circuit-breaker mechanics deterministically,
 * completely decoupled from SimulatorPaymentProviderAdapter's own amount-driven business rules.
 */
public class FakePaymentProviderPort implements PaymentProviderPort {

  private final Deque<Supplier<ProviderAuthorizationOutcome>> scriptedResponses =
      new ArrayDeque<>();
  private int callCount = 0;

  public FakePaymentProviderPort thenApprove() {
    scriptedResponses.add(ProviderAuthorizationOutcome.Approved::new);
    return this;
  }

  public FakePaymentProviderPort thenDecline(String reasonCode, String reasonDetail) {
    scriptedResponses.add(
        () -> new ProviderAuthorizationOutcome.Declined(reasonCode, reasonDetail));
    return this;
  }

  public FakePaymentProviderPort thenTimeOut() {
    scriptedResponses.add(
        () -> {
          throw new ProviderTimeoutException("fake timeout");
        });
    return this;
  }

  public FakePaymentProviderPort thenTemporaryError() {
    scriptedResponses.add(
        () -> {
          throw new ProviderTemporaryErrorException("fake temporary error");
        });
    return this;
  }

  @Override
  public ProviderAuthorizationOutcome authorize(ProviderAuthorizationRequest request) {
    callCount++;
    Supplier<ProviderAuthorizationOutcome> next = scriptedResponses.poll();
    if (next == null) {
      throw new IllegalStateException("FakePaymentProviderPort called more times than scripted");
    }
    return next.get();
  }

  public int callCount() {
    return callCount;
  }
}
