package com.ahmedali.fulfillops.payment.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ahmedali.fulfillops.payment.domain.PaymentAttemptOutcome;
import com.ahmedali.fulfillops.payment.provider.FakePaymentProviderPort;
import com.ahmedali.fulfillops.payment.provider.ProviderAuthorizationOutcome;
import com.ahmedali.fulfillops.payment.provider.ProviderAuthorizationRequest;
import com.ahmedali.fulfillops.payment.provider.ProviderTemporaryErrorException;
import com.ahmedali.fulfillops.payment.provider.ProviderTimeoutException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Proves PaymentAuthorizationClient's retry and circuit-breaker mechanics in isolation, using
 * FakePaymentProviderPort (not SimulatorPaymentProviderAdapter, not HTTP, not randomness) so the
 * behavior under test is purely "how does the client react to what the port does," per the
 * requirement for a fake provider port.
 */
class PaymentAuthorizationClientTest {

  private static final ProviderAuthorizationRequest REQUEST =
      new ProviderAuthorizationRequest(
          UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("50.00"), "USD", 0);

  @Test
  void approvedOnTheFirstAttemptRecordsExactlyOneAttempt() {
    FakePaymentProviderPort port = new FakePaymentProviderPort().thenApprove();
    PaymentAuthorizationClient client = clientWithRetry(3, port);
    List<Recorded> attempts = new ArrayList<>();

    ProviderAuthorizationOutcome outcome =
        client.authorize(REQUEST, 0, recordingListener(attempts));

    assertThat(outcome).isInstanceOf(ProviderAuthorizationOutcome.Approved.class);
    assertThat(attempts).containsExactly(new Recorded(1, PaymentAttemptOutcome.APPROVED, null));
  }

  @Test
  void declinedOnTheFirstAttemptIsNeverRetried() {
    FakePaymentProviderPort port =
        new FakePaymentProviderPort().thenDecline("SIMULATED_CARD_DECLINED", "no soup for you");
    PaymentAuthorizationClient client = clientWithRetry(3, port);
    List<Recorded> attempts = new ArrayList<>();

    ProviderAuthorizationOutcome outcome =
        client.authorize(REQUEST, 0, recordingListener(attempts));

    assertThat(outcome).isInstanceOf(ProviderAuthorizationOutcome.Declined.class);
    assertThat(port.callCount()).isEqualTo(1);
    assertThat(attempts)
        .containsExactly(new Recorded(1, PaymentAttemptOutcome.DECLINED, "no soup for you"));
  }

  @Test
  void timeoutThenSuccessRecoversWithinTheRetryBudget() {
    FakePaymentProviderPort port =
        new FakePaymentProviderPort().thenTimeOut().thenTimeOut().thenApprove();
    PaymentAuthorizationClient client = clientWithRetry(3, port);
    List<Recorded> attempts = new ArrayList<>();

    ProviderAuthorizationOutcome outcome =
        client.authorize(REQUEST, 0, recordingListener(attempts));

    assertThat(outcome).isInstanceOf(ProviderAuthorizationOutcome.Approved.class);
    assertThat(attempts)
        .containsExactly(
            new Recorded(1, PaymentAttemptOutcome.TIMEOUT, "fake timeout"),
            new Recorded(2, PaymentAttemptOutcome.TIMEOUT, "fake timeout"),
            new Recorded(3, PaymentAttemptOutcome.APPROVED, null));
  }

  @Test
  void exhaustingEveryRetryAttemptPropagatesTheLastTechnicalFailure() {
    FakePaymentProviderPort port =
        new FakePaymentProviderPort()
            .thenTemporaryError()
            .thenTemporaryError()
            .thenTemporaryError();
    PaymentAuthorizationClient client = clientWithRetry(3, port);
    List<Recorded> attempts = new ArrayList<>();

    assertThatThrownBy(() -> client.authorize(REQUEST, 0, recordingListener(attempts)))
        .isInstanceOf(ProviderTemporaryErrorException.class);

    assertThat(port.callCount()).isEqualTo(3);
    assertThat(attempts).hasSize(3);
    assertThat(attempts).allMatch(a -> a.outcome() == PaymentAttemptOutcome.TEMPORARY_ERROR);
  }

  @Test
  void attemptNumberingContinuesFromTheSuppliedStartingNumber() {
    FakePaymentProviderPort port = new FakePaymentProviderPort().thenApprove();
    PaymentAuthorizationClient client = clientWithRetry(3, port);
    List<Recorded> attempts = new ArrayList<>();

    // Simulates a redelivery: 2 attempts already happened (and were recorded) on a prior
    // Kafka delivery of the same message, so this call must continue numbering from 3, not
    // restart at 1 — see AuthorizationService.
    client.authorize(REQUEST, 2, recordingListener(attempts));

    assertThat(attempts).containsExactly(new Recorded(3, PaymentAttemptOutcome.APPROVED, null));
  }

  @Test
  void anOpenCircuitRejectsTheCallWithoutInvokingTheProviderAndRecordsCircuitOpen() {
    FakePaymentProviderPort port =
        new FakePaymentProviderPort()
            .thenTemporaryError()
            .thenTemporaryError()
            .thenTemporaryError()
            .thenTemporaryError();
    CircuitBreaker circuitBreaker = circuitBreaker(4, 4);
    Retry retry =
        retry(1); // no in-process retry, so each authorize() call is exactly one provider call
    PaymentAuthorizationClient client = new PaymentAuthorizationClient(port, circuitBreaker, retry);

    // 4 failing calls exhaust the sliding window at 100% failure, tripping the circuit open.
    for (int i = 0; i < 4; i++) {
      List<Recorded> ignored = new ArrayList<>();
      assertThatThrownBy(() -> client.authorize(REQUEST, 0, recordingListener(ignored)))
          .isInstanceOf(ProviderTemporaryErrorException.class);
    }
    assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

    List<Recorded> attempts = new ArrayList<>();
    assertThatThrownBy(() -> client.authorize(REQUEST, 0, recordingListener(attempts)))
        .isInstanceOf(CallNotPermittedException.class);

    assertThat(port.callCount()).isEqualTo(4);
    assertThat(attempts)
        .containsExactly(
            new Recorded(1, PaymentAttemptOutcome.CIRCUIT_OPEN, "circuit breaker open"));
  }

  @Test
  void theCircuitRecoversThroughHalfOpenAfterTheWaitDurationElapses() throws InterruptedException {
    FakePaymentProviderPort port =
        new FakePaymentProviderPort()
            .thenTemporaryError()
            .thenTemporaryError()
            .thenTemporaryError()
            .thenTemporaryError()
            .thenApprove();
    CircuitBreaker circuitBreaker =
        CircuitBreaker.of(
            "test",
            CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMillis(50))
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordExceptions(
                    ProviderTimeoutException.class, ProviderTemporaryErrorException.class)
                .build());
    Retry retry = retry(1);
    PaymentAuthorizationClient client = new PaymentAuthorizationClient(port, circuitBreaker, retry);

    for (int i = 0; i < 4; i++) {
      List<Recorded> ignored = new ArrayList<>();
      assertThatThrownBy(() -> client.authorize(REQUEST, 0, recordingListener(ignored)))
          .isInstanceOf(ProviderTemporaryErrorException.class);
    }
    assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

    Thread.sleep(100); // longer than waitDurationInOpenState, so the next call is a half-open trial

    List<Recorded> attempts = new ArrayList<>();
    ProviderAuthorizationOutcome outcome =
        client.authorize(REQUEST, 0, recordingListener(attempts));

    assertThat(outcome).isInstanceOf(ProviderAuthorizationOutcome.Approved.class);
    assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(attempts).containsExactly(new Recorded(1, PaymentAttemptOutcome.APPROVED, null));
  }

  private static PaymentAuthorizationClient clientWithRetry(
      int maxAttempts, FakePaymentProviderPort port) {
    return new PaymentAuthorizationClient(port, circuitBreaker(100, 100), retry(maxAttempts));
  }

  private static CircuitBreaker circuitBreaker(int slidingWindowSize, int minimumNumberOfCalls) {
    return CircuitBreaker.of(
        "test-" + UUID.randomUUID(),
        CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(slidingWindowSize)
            .minimumNumberOfCalls(minimumNumberOfCalls)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .recordExceptions(ProviderTimeoutException.class, ProviderTemporaryErrorException.class)
            .build());
  }

  private static Retry retry(int maxAttempts) {
    return Retry.of(
        "test-" + UUID.randomUUID(),
        RetryConfig.custom()
            .maxAttempts(maxAttempts)
            .intervalFunction(IntervalFunction.ofExponentialBackoff(5, 1.5))
            .retryExceptions(ProviderTimeoutException.class, ProviderTemporaryErrorException.class)
            .build());
  }

  private static PaymentAttemptListener recordingListener(List<Recorded> sink) {
    return (attemptNumber, outcome, detail) ->
        sink.add(new Recorded(attemptNumber, outcome, detail));
  }

  private record Recorded(int attemptNumber, PaymentAttemptOutcome outcome, String detail) {}
}
