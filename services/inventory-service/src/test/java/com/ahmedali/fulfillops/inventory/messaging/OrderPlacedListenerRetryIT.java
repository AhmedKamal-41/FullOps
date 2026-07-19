package com.ahmedali.fulfillops.inventory.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.ahmedali.fulfillops.inventory.config.TestSecurityConfig;
import com.ahmedali.fulfillops.inventory.config.TestcontainersConfiguration;
import com.ahmedali.fulfillops.inventory.service.ReservationService;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves OrderPlacedListener's own @RetryableTopic wiring, now that the Phase 3 self-consuming
 * scaffold listener that used to prove this generically has been replaced by this real one.
 * ReservationService is mocked only for the transient-failure case; the malformed-payload case
 * exercises real production code (OrderPlacedListener.parseItemsOrFailNonRetryably) with no mocking
 * needed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class OrderPlacedListenerRetryIT {

  @MockitoBean private ReservationService reservationService;

  @Autowired private KafkaTemplate<String, String> kafkaTemplate;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private KafkaConnectionDetails kafkaConnectionDetails;

  @Value("${app.messaging.order-events-topic}")
  private String orderEventsTopic;

  @Test
  void aMalformedPayloadSkipsRetryAndGoesStraightToTheDlt() {
    UUID eventId = UUID.randomUUID();
    publishDirectlyToTopic(eventId, Map.of("items", List.of()));

    ConsumerRecord<String, String> onDlt =
        pollFor(orderEventsTopic + "-dlt", eventId.toString(), Duration.ofSeconds(15));
    assertThat(onDlt).as("a malformed payload must reach the DLT").isNotNull();

    ConsumerRecord<String, String> onRetryTopic =
        pollFor(orderEventsTopic + "-retry-500", eventId.toString(), Duration.ofSeconds(3));
    assertThat(onRetryTopic).as("a malformed payload must never appear on a retry topic").isNull();
  }

  @Test
  void aGenuineTransientFailureIsRetriedBeforeReachingTheDlt() {
    when(reservationService.reserve(any(), any(), any(), any()))
        .thenThrow(new RuntimeException("simulated transient failure, e.g. database unavailable"));

    UUID eventId = UUID.randomUUID();
    publishDirectlyToTopic(
        eventId,
        Map.of(
            "items",
            List.of(
                Map.of(
                    "sku",
                    "WIDGET-BLUE-M",
                    "quantity",
                    1,
                    "unitPrice",
                    Map.of("currencyCode", "USD", "amount", "9.99")))));

    ConsumerRecord<String, String> onRetryTopic =
        pollFor(orderEventsTopic + "-retry-500", eventId.toString(), Duration.ofSeconds(15));
    assertThat(onRetryTopic).as("expected the event to reach the first retry topic").isNotNull();

    ConsumerRecord<String, String> onDlt =
        pollFor(orderEventsTopic + "-dlt", eventId.toString(), Duration.ofSeconds(30));
    assertThat(onDlt)
        .as("expected the event to reach the DLT after exhausting retries")
        .isNotNull();
  }

  private void publishDirectlyToTopic(UUID eventId, Map<String, Object> payload) {
    kafkaTemplate.send(orderEventsTopic, eventId.toString(), envelopeJson(eventId, payload));
  }

  private String envelopeJson(UUID eventId, Map<String, Object> payload) {
    EventEnvelope envelope =
        new EventEnvelope(
            eventId,
            "OrderPlaced",
            1,
            Instant.now(),
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            "order-service",
            objectMapper.valueToTree(payload));
    return objectMapper.writeValueAsString(envelope);
  }

  private ConsumerRecord<String, String> pollFor(String topicName, String key, Duration timeout) {
    Properties props = new Properties();
    props.put(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
        String.join(",", kafkaConnectionDetails.getBootstrapServers()));
    props.put(
        ConsumerConfig.GROUP_ID_CONFIG, "order-placed-retry-test-verifier-" + UUID.randomUUID());
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
