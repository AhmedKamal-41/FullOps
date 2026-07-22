package com.ahmedali.fulfillops.payment.resilience;

import com.ahmedali.fulfillops.payment.provider.ProviderTemporaryErrorException;
import com.ahmedali.fulfillops.payment.provider.ProviderTimeoutException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires Resilience4j's framework-agnostic core libraries (resilience4j-circuitbreaker,
 * resilience4j-retry, resilience4j-micrometer) directly, not the resilience4j-spring-boot3/4
 * starter — see docs/ARCHITECTURE.md for why: no resilience4j Spring Boot
 * starter with verified Spring Boot 4.1 support was available on Maven Central when this was
 * written. PaymentAuthorizationClient is what actually decorates provider calls with the beans
 * built here.
 *
 * <p>The registry is created before the circuit breaker/retry instance so the instance returned as
 * a bean is the same one the registry (and therefore the Micrometer metrics binder) tracks —
 * creating a standalone instance via CircuitBreaker.of(...)/Retry.of(...) and a separately
 * constructed registry would silently bind metrics to an empty registry that never sees a real
 * call.
 */
@Configuration
public class PaymentProviderResilienceConfig {

  private static final String PROVIDER_NAME = "payment-provider";

  @Bean
  public CircuitBreaker paymentProviderCircuitBreaker(
      @Value("${app.payment.provider.circuit-breaker.sliding-window-size:10}")
          int slidingWindowSize,
      @Value("${app.payment.provider.circuit-breaker.minimum-number-of-calls:5}")
          int minimumNumberOfCalls,
      @Value("${app.payment.provider.circuit-breaker.failure-rate-threshold:50}")
          float failureRateThreshold,
      @Value("${app.payment.provider.circuit-breaker.wait-duration-in-open-state-ms:30000}")
          long waitDurationInOpenStateMillis,
      @Value("${app.payment.provider.circuit-breaker.permitted-calls-in-half-open-state:3}")
          int permittedCallsInHalfOpenState,
      MeterRegistry meterRegistry) {
    CircuitBreakerConfig config =
        CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(slidingWindowSize)
            .minimumNumberOfCalls(minimumNumberOfCalls)
            .failureRateThreshold(failureRateThreshold)
            .waitDurationInOpenState(Duration.ofMillis(waitDurationInOpenStateMillis))
            .permittedNumberOfCallsInHalfOpenState(permittedCallsInHalfOpenState)
            .recordExceptions(ProviderTimeoutException.class, ProviderTemporaryErrorException.class)
            .build();

    CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
    CircuitBreaker circuitBreaker = registry.circuitBreaker(PROVIDER_NAME, config);
    TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
    return circuitBreaker;
  }

  @Bean
  public Retry paymentProviderRetry(
      @Value("${app.payment.provider.retry.max-attempts:3}") int maxAttempts,
      @Value("${app.payment.provider.retry.initial-interval-ms:200}") long initialIntervalMillis,
      @Value("${app.payment.provider.retry.backoff-multiplier:2.0}") double backoffMultiplier,
      MeterRegistry meterRegistry) {
    RetryConfig config =
        RetryConfig.custom()
            .maxAttempts(maxAttempts)
            .intervalFunction(
                IntervalFunction.ofExponentialBackoff(initialIntervalMillis, backoffMultiplier))
            .retryExceptions(ProviderTimeoutException.class, ProviderTemporaryErrorException.class)
            .build();

    RetryRegistry registry = RetryRegistry.of(config);
    Retry retry = registry.retry(PROVIDER_NAME, config);
    TaggedRetryMetrics.ofRetryRegistry(registry).bindTo(meterRegistry);
    return retry;
  }
}
