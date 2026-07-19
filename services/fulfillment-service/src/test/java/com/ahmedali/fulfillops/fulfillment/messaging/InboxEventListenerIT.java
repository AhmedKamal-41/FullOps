package com.ahmedali.fulfillops.fulfillment.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ahmedali.fulfillops.fulfillment.config.TestSecurityConfig;
import com.ahmedali.fulfillops.fulfillment.config.TestcontainersConfiguration;
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
import org.junit.jupiter.api.AfterEach;
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
 * Covers Phase 3's remaining required scenarios: duplicate delivery is a no-op, processing failure
 * rolls back the whole transaction, and retryable vs. non-retryable failures are routed differently
 * (retry topics then DLT, vs. straight to DLT).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class InboxEventListenerIT {

  @Autowired private InboxEventListener inboxEventListener;

  @Autowired private InboxEventRepository inboxEventRepository;

  @Autowired private KafkaTemplate<String, String> kafkaTemplate;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private KafkaConnectionDetails kafkaConnectionDetails;

  @Value("${app.messaging.topic}")
  private String topic;

  private KafkaConsumer<String, String> verifierConsumer;

  @AfterEach
  void closeVerifierConsumerIfOpen() {
    if (verifierConsumer != null) {
      verifierConsumer.close();
    }
  }

  @Test
  void secondDeliveryOfTheSameEventIsANoOp() {
    String envelopeJson = envelopeJson("DuplicateDeliveryTest", Map.of());

    inboxEventListener.onMessage(envelopeJson);
    inboxEventListener.onMessage(envelopeJson);

    long matchingRows =
        inboxEventRepository.findAll().stream()
            .filter(row -> row.getEventType().equals("DuplicateDeliveryTest"))
            .count();
    assertThat(matchingRows).isEqualTo(1);
  }

  @Test
  void aProcessingFailureRollsBackTheWholeTransactionSoNoInboxRowIsLeftBehind() {
    UUID eventId = UUID.randomUUID();
    String envelopeJson =
        envelopeJson(eventId, "RollbackTest", Map.of("simulateFailure", "retryable"));

    assertThatThrownBy(() -> inboxEventListener.onMessage(envelopeJson))
        .isInstanceOf(RuntimeException.class);

    boolean rowExists =
        inboxEventRepository.findAll().stream()
            .anyMatch(row -> row.getId().getEventId().equals(eventId));
    assertThat(rowExists).as("no inbox row should exist after a rolled-back failure").isFalse();
  }

  @Test
  void retryableFailuresGoThroughARetryTopicBeforeReachingTheDlt() {
    UUID eventId = UUID.randomUUID();
    publishDirectlyToTopic(eventId, "RetryableFailureTest", Map.of("simulateFailure", "retryable"));

    ConsumerRecord<String, String> onRetryTopic =
        pollFor(topic + "-retry-500", eventId.toString(), Duration.ofSeconds(15));
    assertThat(onRetryTopic).as("expected the event to reach the first retry topic").isNotNull();

    ConsumerRecord<String, String> onDlt =
        pollFor(topic + "-dlt", eventId.toString(), Duration.ofSeconds(30));
    assertThat(onDlt)
        .as("expected the event to reach the DLT after exhausting retries")
        .isNotNull();
  }

  @Test
  void nonRetryableFailuresSkipRetryTopicsAndGoStraightToTheDlt() {
    UUID eventId = UUID.randomUUID();
    publishDirectlyToTopic(
        eventId, "NonRetryableFailureTest", Map.of("simulateFailure", "non-retryable"));

    ConsumerRecord<String, String> onDlt =
        pollFor(topic + "-dlt", eventId.toString(), Duration.ofSeconds(15));
    assertThat(onDlt).as("expected the event to reach the DLT immediately").isNotNull();

    ConsumerRecord<String, String> onRetryTopic =
        pollFor(topic + "-retry-500", eventId.toString(), Duration.ofSeconds(3));
    assertThat(onRetryTopic)
        .as("a non-retryable failure must never appear on a retry topic")
        .isNull();
  }

  private void publishDirectlyToTopic(UUID eventId, String eventType, Map<String, Object> payload) {
    String json = envelopeJson(eventId, eventType, payload);
    kafkaTemplate.send(topic, eventId.toString(), json);
  }

  private String envelopeJson(String eventType, Map<String, Object> payload) {
    return envelopeJson(UUID.randomUUID(), eventType, payload);
  }

  private String envelopeJson(UUID eventId, String eventType, Map<String, Object> payload) {
    EventEnvelope envelope =
        new EventEnvelope(
            eventId,
            eventType,
            1,
            Instant.now(),
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            "fulfillment-service",
            objectMapper.valueToTree(payload));
    return objectMapper.writeValueAsString(envelope);
  }

  private ConsumerRecord<String, String> pollFor(String topicName, String key, Duration timeout) {
    Properties props = new Properties();
    props.put(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
        String.join(",", kafkaConnectionDetails.getBootstrapServers()));
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "inbox-test-verifier-" + UUID.randomUUID());
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
