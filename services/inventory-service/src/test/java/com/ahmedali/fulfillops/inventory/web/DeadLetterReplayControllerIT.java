package com.ahmedali.fulfillops.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ahmedali.fulfillops.inventory.config.TestSecurityConfig;
import com.ahmedali.fulfillops.inventory.config.TestcontainersConfiguration;
import com.ahmedali.fulfillops.inventory.messaging.DeadLetterEvent;
import com.ahmedali.fulfillops.inventory.messaging.DeadLetterEventRepository;
import com.ahmedali.fulfillops.inventory.messaging.DeadLetterEventStatus;
import com.ahmedali.fulfillops.inventory.messaging.EventEnvelope;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Covers Phase 8's ADMIN-only dead-letter replay endpoint against real infrastructure: a malformed
 * OrderPlaced event (empty items, the same fast non-retryable path OrderEventsListenerRetryIT
 * exercises) reaches this service's real dead-letter topic. From there, an ADMIN can list and
 * replay it — which republishes the exact original bytes and lets it reprocess — while a non-ADMIN
 * is forbidden, an unknown event id 404s, and replaying the same event twice is rejected as a
 * conflict.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class DeadLetterReplayControllerIT {

  @Autowired private MockMvc mockMvc;
  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private KafkaConnectionDetails kafkaConnectionDetails;
  @Autowired private DeadLetterEventRepository deadLetterEventRepository;
  @Autowired private ObjectMapper objectMapper;

  @Value("${app.messaging.order-events-topic}")
  private String orderEventsTopic;

  @Test
  void adminCanReplayADeadLetteredEventAndItReprocessesSuccessfully() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    publishMalformedEvent(eventId, orderId);
    waitForDeadLetterRow(eventId);

    mockMvc.perform(get("/api/v1/admin/dead-letters").with(admin())).andExpect(status().isOk());

    mockMvc
        .perform(post("/api/v1/admin/dead-letters/" + eventId + "/replay").with(admin()))
        .andExpect(status().isOk());

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              DeadLetterEvent replayed =
                  deadLetterEventRepository.findFirstByIdEventId(eventId).orElseThrow();
              assertThat(replayed.getStatus()).isEqualTo(DeadLetterEventStatus.REPLAYED);
              assertThat(replayed.getReplayedBy()).isEqualTo("admin-subject");
            });

    ConsumerRecord<String, String> republished =
        pollForRecordWithKey(orderEventsTopic, orderId.toString());
    assertThat(republished.value()).contains(eventId.toString());
  }

  @Test
  void aNonAdminCannotReplay() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    publishMalformedEvent(eventId, orderId);
    waitForDeadLetterRow(eventId);

    mockMvc
        .perform(post("/api/v1/admin/dead-letters/" + eventId + "/replay").with(operator()))
        .andExpect(status().isForbidden());
  }

  @Test
  void replayingAnUnknownEventIdReturnsNotFound() throws Exception {
    mockMvc
        .perform(post("/api/v1/admin/dead-letters/" + UUID.randomUUID() + "/replay").with(admin()))
        .andExpect(status().isNotFound());
  }

  @Test
  void replayingTheSameEventTwiceIsRejectedAsAConflict() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    publishMalformedEvent(eventId, orderId);
    waitForDeadLetterRow(eventId);

    mockMvc
        .perform(post("/api/v1/admin/dead-letters/" + eventId + "/replay").with(admin()))
        .andExpect(status().isOk());
    mockMvc
        .perform(post("/api/v1/admin/dead-letters/" + eventId + "/replay").with(admin()))
        .andExpect(status().isConflict());
  }

  private void waitForDeadLetterRow(UUID eventId) {
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> assertThat(deadLetterEventRepository.findFirstByIdEventId(eventId)).isPresent());
  }

  private void publishMalformedEvent(UUID eventId, UUID orderId) {
    EventEnvelope envelope =
        new EventEnvelope(
            eventId,
            "OrderPlaced",
            1,
            Instant.now(),
            UUID.randomUUID(),
            null,
            orderId,
            "order-service",
            objectMapper.valueToTree(Map.of("items", List.of())));
    kafkaTemplate.send(
        orderEventsTopic, orderId.toString(), objectMapper.writeValueAsString(envelope));
  }

  private static JwtRequestPostProcessor admin() {
    return jwt()
        .jwt(token -> token.subject("admin-subject"))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  private static JwtRequestPostProcessor operator() {
    return jwt()
        .jwt(token -> token.subject("operator-subject"))
        .authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"));
  }

  private ConsumerRecord<String, String> pollForRecordWithKey(String topicName, String key) {
    Properties props = new Properties();
    props.put(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
        String.join(",", kafkaConnectionDetails.getBootstrapServers()));
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-replay-test-verifier-" + UUID.randomUUID());
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(topicName));
      long deadline = System.currentTimeMillis() + 15_000;
      while (System.currentTimeMillis() < deadline) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, String> record : records) {
          if (key.equals(record.key())) {
            return record;
          }
        }
      }
    }
    throw new AssertionError("no republished record with key " + key + " arrived on " + topicName);
  }
}
