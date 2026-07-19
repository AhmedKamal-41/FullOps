package com.ahmedali.fulfillops.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ahmedali.fulfillops.payment.config.TestSecurityConfig;
import com.ahmedali.fulfillops.payment.config.TestcontainersConfiguration;
import com.ahmedali.fulfillops.payment.domain.OrderPaymentContext;
import com.ahmedali.fulfillops.payment.domain.OrderPaymentContextRepository;
import com.ahmedali.fulfillops.payment.domain.PaymentAttempt;
import com.ahmedali.fulfillops.payment.domain.PaymentAttemptRepository;
import com.ahmedali.fulfillops.payment.domain.PaymentRepository;
import com.ahmedali.fulfillops.payment.messaging.EventEnvelope;
import com.ahmedali.fulfillops.payment.provider.ProviderTemporaryErrorException;
import com.ahmedali.fulfillops.payment.provider.ProviderUnavailableException;
import com.ahmedali.fulfillops.payment.service.AuthorizationService;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves retry/timeout/circuit-breaker behavior against the real, seeded simulator_rules table and
 * a real Spring-managed CircuitBreaker/Retry pair — the integration-level complement to
 * PaymentAuthorizationClientTest's isolated unit coverage. CircuitBreaker is a process-wide
 * singleton bean, so every test resets it first; that is what keeps these tests independent of
 * execution order, not any assumption about which test happens to run first.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class ResilienceIT {

  // Fictional demo amounts seeded by V2__payments.sql.
  private static final String TIMEOUT_THEN_RECOVERS_AMOUNT = "9997.00";
  private static final String ALWAYS_TEMPORARY_ERROR_AMOUNT = "9998.00";
  private static final String ALWAYS_TIMEOUT_AMOUNT = "9999.00";
  private static final String NO_RULE_AMOUNT = "50.00";

  @Autowired private AuthorizationService authorizationService;
  @Autowired private OrderPaymentContextRepository orderPaymentContextRepository;
  @Autowired private PaymentRepository paymentRepository;
  @Autowired private PaymentAttemptRepository paymentAttemptRepository;
  @Autowired private CircuitBreaker paymentProviderCircuitBreaker;

  @Autowired
  private com.ahmedali.fulfillops.payment.messaging.InventoryReservedListener
      inventoryReservedListener;

  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private KafkaConnectionDetails kafkaConnectionDetails;

  @Value("${app.messaging.inventory-events-topic}")
  private String inventoryEventsTopic;

  @BeforeEach
  void resetTheSharedCircuitBreaker() {
    paymentProviderCircuitBreaker.reset();
  }

  @Test
  void timeoutThenSuccessAcrossInProcessRetryAttemptsEventuallyAuthorizes() {
    UUID orderId = seedOrderContext(TIMEOUT_THEN_RECOVERS_AMOUNT);

    authorizationService.authorize(orderId, UUID.randomUUID(), UUID.randomUUID());

    assertThat(paymentRepository.findByOrderId(orderId)).isPresent();
    assertThat(paymentRepository.findByOrderId(orderId).orElseThrow().getStatus().name())
        .isEqualTo("AUTHORIZED");
    List<PaymentAttempt> attempts =
        paymentAttemptRepository.findByOrderIdOrderByAttemptNumber(orderId);
    assertThat(attempts)
        .extracting(a -> a.getOutcome().name())
        .containsExactly("TIMEOUT", "TIMEOUT", "APPROVED");
  }

  @Test
  void exhaustingTheInProcessRetryBudgetPropagatesAndRecordsEveryFailedAttempt() {
    UUID orderId = seedOrderContext(ALWAYS_TEMPORARY_ERROR_AMOUNT);

    assertThatThrownBy(
            () -> authorizationService.authorize(orderId, UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(ProviderTemporaryErrorException.class);

    assertThat(paymentRepository.findByOrderId(orderId)).isEmpty();
    List<PaymentAttempt> attempts =
        paymentAttemptRepository.findByOrderIdOrderByAttemptNumber(orderId);
    assertThat(attempts).hasSize(3);
    assertThat(attempts).allMatch(a -> a.getOutcome().name().equals("TEMPORARY_ERROR"));
    // Only 3 calls happened, below the test profile's minimumNumberOfCalls (4), so the
    // circuit is not yet evaluated for opening.
    assertThat(paymentProviderCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  void
      enoughConsecutiveTechnicalFailuresOpenTheCircuitAndTheNextCallIsRejectedWithoutCallingTheProvider() {
    // Each order's authorize() call retries up to 3 times (app.payment.provider.retry.max-attempts
    // in application-test.yml), all failing for this amount, so 2 orders already supply more than
    // the test profile's minimumNumberOfCalls (4) of consecutive 100%-failure circuit breaker
    // calls.
    for (int i = 0; i < 2; i++) {
      UUID orderId = seedOrderContext(ALWAYS_TEMPORARY_ERROR_AMOUNT);
      // Whichever order's retry sequence happens to cross the circuit breaker's failure
      // threshold will see a CallNotPermittedException on its own later attempts instead of
      // the raw technical failure — both are expected, non-business, un-retried-by-business-
      // logic outcomes here, so either is acceptable.
      assertThatThrownBy(
              () -> authorizationService.authorize(orderId, UUID.randomUUID(), UUID.randomUUID()))
          .isInstanceOfAny(ProviderUnavailableException.class, CallNotPermittedException.class);
    }
    assertThat(paymentProviderCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

    UUID rejectedOrderId = seedOrderContext(ALWAYS_TEMPORARY_ERROR_AMOUNT);
    assertThatThrownBy(
            () ->
                authorizationService.authorize(
                    rejectedOrderId, UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(CallNotPermittedException.class);

    List<PaymentAttempt> attempts =
        paymentAttemptRepository.findByOrderIdOrderByAttemptNumber(rejectedOrderId);
    assertThat(attempts).hasSize(1);
    assertThat(attempts.get(0).getOutcome().name()).isEqualTo("CIRCUIT_OPEN");
  }

  @Test
  void theCircuitRecoversThroughHalfOpenOnceTheWaitDurationElapses() throws InterruptedException {
    for (int i = 0; i < 2; i++) {
      UUID orderId = seedOrderContext(ALWAYS_TEMPORARY_ERROR_AMOUNT);
      // Whichever order's retry sequence happens to cross the circuit breaker's failure
      // threshold will see a CallNotPermittedException on its own later attempts instead of
      // the raw technical failure — both are expected, non-business, un-retried-by-business-
      // logic outcomes here, so either is acceptable.
      assertThatThrownBy(
              () -> authorizationService.authorize(orderId, UUID.randomUUID(), UUID.randomUUID()))
          .isInstanceOfAny(ProviderUnavailableException.class, CallNotPermittedException.class);
    }
    assertThat(paymentProviderCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

    // Longer than app.payment.provider.circuit-breaker.wait-duration-in-open-state-ms (200ms) in
    // application-test.yml.
    Thread.sleep(400);

    UUID recoveringOrderId = seedOrderContext(NO_RULE_AMOUNT);
    authorizationService.authorize(recoveringOrderId, UUID.randomUUID(), UUID.randomUUID());

    assertThat(paymentRepository.findByOrderId(recoveringOrderId).orElseThrow().getStatus().name())
        .isEqualTo("AUTHORIZED");
    assertThat(paymentProviderCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  void retryExhaustionOnARealKafkaDeliveryReachesTheDeadLetterTopicAfterKafkaLevelRetries() {
    UUID orderId = seedOrderContext(ALWAYS_TIMEOUT_AMOUNT);
    UUID correlationId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    // Keyed by eventId, not orderId (the real production key — see ADR 0009), purely so this
    // test can find its own message on the retry/DLT topics by a key nothing else collides
    // with; same shortcut InboxEventListenerIT and OrderPlacedListenerRetryIT already use.
    kafkaTemplate.send(
        inventoryEventsTopic,
        eventId.toString(),
        inventoryReservedEnvelopeJson(eventId, orderId, correlationId));

    ConsumerRecord<String, String> onDlt =
        pollFor(inventoryEventsTopic + "-dlt", eventId.toString(), Duration.ofSeconds(30));
    assertThat(onDlt)
        .as("expected the event to reach the DLT after exhausting Kafka-level retries")
        .isNotNull();
    assertThat(paymentRepository.findByOrderId(orderId)).isEmpty();
  }

  private UUID seedOrderContext(String amount) {
    UUID orderId = UUID.randomUUID();
    orderPaymentContextRepository.save(
        new OrderPaymentContext(
            orderId, UUID.randomUUID(), new BigDecimal(amount), "USD", UUID.randomUUID()));
    return orderId;
  }

  private String inventoryReservedEnvelopeJson(UUID eventId, UUID orderId, UUID correlationId) {
    Map<String, Object> payload =
        Map.of(
            "reservationId", UUID.randomUUID().toString(),
            "items", List.of(Map.of("sku", "WIDGET-BLUE-M", "quantity", 1)));
    EventEnvelope envelope =
        new EventEnvelope(
            eventId,
            "InventoryReserved",
            1,
            Instant.now(),
            correlationId,
            UUID.randomUUID(),
            orderId,
            "inventory-service",
            objectMapper.valueToTree(payload));
    return objectMapper.writeValueAsString(envelope);
  }

  private ConsumerRecord<String, String> pollFor(String topicName, String key, Duration timeout) {
    Properties props = new Properties();
    props.put(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
        String.join(",", kafkaConnectionDetails.getBootstrapServers()));
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "resilience-test-verifier-" + UUID.randomUUID());
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(topicName));
      long deadline = System.currentTimeMillis() + timeout.toMillis();
      while (System.currentTimeMillis() < deadline) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, String> record : records) {
          if (key.equals(record.key())) {
            return record;
          }
        }
      }
      return null;
    }
  }
}
