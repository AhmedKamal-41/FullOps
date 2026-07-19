package com.ahmedali.fulfillops.payment.resilience;

import com.ahmedali.fulfillops.payment.domain.PaymentAttemptOutcome;
import com.ahmedali.fulfillops.payment.provider.PaymentProviderPort;
import com.ahmedali.fulfillops.payment.provider.ProviderAuthorizationOutcome;
import com.ahmedali.fulfillops.payment.provider.ProviderAuthorizationRequest;
import com.ahmedali.fulfillops.payment.provider.ProviderTemporaryErrorException;
import com.ahmedali.fulfillops.payment.provider.ProviderTimeoutException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Decorates PaymentProviderPort calls with a bounded retry and a circuit breaker (see
 * PaymentProviderResilienceConfig for how those are configured), and reports every raw attempt to
 * the caller-supplied PaymentAttemptListener so it can be persisted for audit regardless of the
 * final outcome. Retry wraps the circuit breaker, not the other way around — a call rejected
 * because the circuit is open (CallNotPermittedException) is not one of retry's configured
 * retryExceptions, so it fails fast on the first attempt instead of retrying against a circuit that
 * has already decided not to let calls through.
 */
@Component
public class PaymentAuthorizationClient {

  private final PaymentProviderPort providerPort;
  private final CircuitBreaker circuitBreaker;
  private final Retry retry;

  public PaymentAuthorizationClient(
      PaymentProviderPort providerPort, CircuitBreaker circuitBreaker, Retry retry) {
    this.providerPort = providerPort;
    this.circuitBreaker = circuitBreaker;
    this.retry = retry;
  }

  public ProviderAuthorizationOutcome authorize(
      ProviderAuthorizationRequest request,
      int startingAttemptNumber,
      PaymentAttemptListener listener) {
    AtomicInteger attemptNumber = new AtomicInteger(startingAttemptNumber);

    Supplier<ProviderAuthorizationOutcome> rawAttempt =
        () -> callProviderOnce(request, attemptNumber, listener);
    Supplier<ProviderAuthorizationOutcome> resilientAttempt =
        Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, rawAttempt));

    try {
      return resilientAttempt.get();
    } catch (CallNotPermittedException circuitOpen) {
      listener.onAttempt(
          attemptNumber.incrementAndGet(),
          PaymentAttemptOutcome.CIRCUIT_OPEN,
          "circuit breaker open");
      throw circuitOpen;
    }
  }

  private ProviderAuthorizationOutcome callProviderOnce(
      ProviderAuthorizationRequest request,
      AtomicInteger attemptNumber,
      PaymentAttemptListener listener) {
    int attempt = attemptNumber.incrementAndGet();
    try {
      ProviderAuthorizationOutcome outcome =
          providerPort.authorize(request.withAttemptNumber(attempt));
      recordBusinessOutcome(attempt, outcome, listener);
      return outcome;
    } catch (ProviderTimeoutException timeout) {
      listener.onAttempt(attempt, PaymentAttemptOutcome.TIMEOUT, timeout.getMessage());
      throw timeout;
    } catch (ProviderTemporaryErrorException temporaryError) {
      listener.onAttempt(
          attempt, PaymentAttemptOutcome.TEMPORARY_ERROR, temporaryError.getMessage());
      throw temporaryError;
    }
  }

  private static void recordBusinessOutcome(
      int attempt, ProviderAuthorizationOutcome outcome, PaymentAttemptListener listener) {
    switch (outcome) {
      case ProviderAuthorizationOutcome.Approved ignored ->
          listener.onAttempt(attempt, PaymentAttemptOutcome.APPROVED, null);
      case ProviderAuthorizationOutcome.Declined declined ->
          listener.onAttempt(attempt, PaymentAttemptOutcome.DECLINED, declined.reasonDetail());
    }
  }
}
