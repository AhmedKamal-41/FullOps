package com.ahmedali.fulfillops.payment.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.ahmedali.fulfillops.payment.config.TestSecurityConfig;
import com.ahmedali.fulfillops.payment.config.TestcontainersConfiguration;
import com.ahmedali.fulfillops.payment.service.AuthorizationService;
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
 * Proves a genuinely transient failure recovers on retry instead of always running to the DLT:
 * AuthorizationService fails the first two attempts and succeeds on the third, well within
 * InventoryReservedListener's @RetryableTopic budget of 4 attempts — so it must never reach the
 * dead-letter topic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class InventoryReservedListenerRetryIT {

  @MockitoBean private AuthorizationService authorizationService;

  @Autowired private KafkaTemplate<String, String> kafkaTemplate;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private KafkaConnectionDetails kafkaConnectionDetails;

  @Value("${app.messaging.inventory-events-topic}")
  private String inventoryEventsTopic;

  @Test
  void aTransientFailureRecoversOnRetryInsteadOfReachingTheDlt() {
    doThrow(new RuntimeException("simulated transient failure, attempt 1"))
        .doThrow(new RuntimeException("simulated transient failure, attempt 2"))
        .doNothing()
        .when(authorizationService)
        .authorize(any(), any(), any());

    UUID orderId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    kafkaTemplate.send(
        inventoryEventsTopic, orderId.toString(), envelope(eventId, "InventoryReserved", orderId));

    verify(authorizationService, timeout(20_000).times(3)).authorize(eq(orderId), any(), any());

    ConsumerRecord<String, String> onDlt =
        pollFor(inventoryEventsTopic + "-dlt", eventId.toString(), Duration.ofSeconds(5));
    assertThat(onDlt)
        .as("a failure that recovers within the retry budget must never reach the DLT")
        .isNull();
  }

  private String envelope(UUID eventId, String eventType, UUID orderId) {
    EventEnvelope envelope =
        new EventEnvelope(
            eventId,
            eventType,
            1,
            Instant.now(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            orderId,
            "inventory-service",
            objectMapper.valueToTree(Map.of()));
    return objectMapper.writeValueAsString(envelope);
  }

  private ConsumerRecord<String, String> pollFor(String topicName, String key, Duration timeout) {
    Properties props = new Properties();
    props.put(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
        String.join(",", kafkaConnectionDetails.getBootstrapServers()));
    props.put(
        ConsumerConfig.GROUP_ID_CONFIG,
        "inventory-reserved-retry-test-verifier-" + UUID.randomUUID());
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
