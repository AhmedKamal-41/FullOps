package com.ahmedali.fulfillops.order.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ahmedali.fulfillops.order.config.TestSecurityConfig;
import com.ahmedali.fulfillops.order.config.TestcontainersConfiguration;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Covers two of Phase 3's required scenarios: outbox persistence, and an outbox event actually
 * reaching Kafka (including correlation propagation into headers). Uses a dedicated consumer group
 * so this test's own verification consumer doesn't compete with the service's real
 * InboxEventListener, which is also live during this test and will independently pick up the same
 * message — Kafka consumer groups each get their own copy, so that's expected, not a conflict.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class OutboxRelayIT {

  @Autowired private OutboxEventWriter outboxEventWriter;

  @Autowired private OutboxEventRepository outboxEventRepository;

  @Autowired private OutboxRelay outboxRelay;

  @Autowired private KafkaConnectionDetails kafkaConnectionDetails;

  @Value("${app.messaging.topic}")
  private String topic;

  private KafkaConsumer<String, String> verifierConsumer;

  @BeforeEach
  void subscribeAsAnIndependentConsumerGroup() {
    Properties props = new Properties();
    props.put(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
        String.join(",", kafkaConnectionDetails.getBootstrapServers()));
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-relay-test-verifier-" + UUID.randomUUID());
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    verifierConsumer = new KafkaConsumer<>(props);
    verifierConsumer.subscribe(java.util.List.of(topic));
  }

  @AfterEach
  void closeVerifierConsumer() {
    verifierConsumer.close();
  }

  @Test
  void writingAnOutboxEventPersistsAPendingRow() {
    UUID aggregateId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();

    UUID eventId =
        outboxEventWriter.write(
            "OutboxPersistenceTest",
            1,
            aggregateId,
            correlationId,
            null,
            Map.of("note", "outbox-persistence"));

    OutboxEvent saved = outboxEventRepository.findById(eventId).orElseThrow();
    assertThat(saved.getState()).isEqualTo(OutboxState.PENDING.name());
    assertThat(saved.getAggregateId()).isEqualTo(aggregateId);
    assertThat(saved.getCorrelationId()).isEqualTo(correlationId);
    assertThat(saved.getAttemptCount()).isZero();
  }

  @Test
  void relayPublishesAPendingRowAndMarksItPublishedWithCorrelationPropagated() {
    UUID aggregateId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();

    UUID eventId =
        outboxEventWriter.write(
            "PublishSuccessTest",
            1,
            aggregateId,
            correlationId,
            null,
            Map.of("note", "publish-success"));

    outboxRelay.pollAndPublish();

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              OutboxEvent stored = outboxEventRepository.findById(eventId).orElseThrow();
              assertThat(stored.getState()).isEqualTo(OutboxState.PUBLISHED.name());
            });

    ConsumerRecord<String, String> record = pollForRecordWithKey(aggregateId.toString());
    assertThat(record.headers().lastHeader("eventId").value())
        .asString()
        .isEqualTo(eventId.toString());
    assertThat(record.headers().lastHeader("correlationId").value())
        .asString()
        .isEqualTo(correlationId.toString());
    assertThat(record.value()).contains("PublishSuccessTest");
  }

  private ConsumerRecord<String, String> pollForRecordWithKey(String key) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      ConsumerRecords<String, String> records = verifierConsumer.poll(Duration.ofMillis(500));
      for (ConsumerRecord<String, String> record : records) {
        if (key.equals(record.key())) {
          return record;
        }
      }
    }
    throw new AssertionError("no record with key " + key + " arrived on topic " + topic);
  }
}
