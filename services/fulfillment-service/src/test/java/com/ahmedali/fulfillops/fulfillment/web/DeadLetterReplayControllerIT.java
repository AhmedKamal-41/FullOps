package com.ahmedali.fulfillops.fulfillment.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ahmedali.fulfillops.fulfillment.config.TestSecurityConfig;
import com.ahmedali.fulfillops.fulfillment.config.TestcontainersConfiguration;
import com.ahmedali.fulfillops.fulfillment.messaging.DeadLetterEvent;
import com.ahmedali.fulfillops.fulfillment.messaging.DeadLetterEventRecorder;
import com.ahmedali.fulfillops.fulfillment.messaging.DeadLetterEventRepository;
import com.ahmedali.fulfillops.fulfillment.messaging.DeadLetterEventStatus;
import com.ahmedali.fulfillops.fulfillment.messaging.EventEnvelope;
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
 * Covers the ADMIN-only dead-letter replay endpoint against real infrastructure: an ADMIN can
 * list and replay a dead-lettered event — which republishes the exact original bytes onto the real
 * original topic and flips its status — while a non-ADMIN is forbidden, an unknown event id 404s,
 * and replaying the same event twice is rejected as a conflict. The dead-letter row itself is
 * seeded directly through DeadLetterEventRecorder (the exact same bean every real @DltHandler in
 * this service calls) rather than by exhausting the real @RetryableTopic backoff schedule — that
 * schedule is proven separately by PaymentAuthorizedListenerRetryIT, and forcing every test in this
 * class through it as well would mean minutes of real Kafka retry-topic rebalancing per test for no
 * extra coverage of what's actually under test here: the replay endpoint.
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
  @Autowired private DeadLetterEventRecorder deadLetterEventRecorder;
  @Autowired private ObjectMapper objectMapper;

  @Value("${app.messaging.payment-events-topic}")
  private String paymentEventsTopic;

  @Test
  void adminCanReplayADeadLetteredEventAndItReprocessesSuccessfully() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    seedDeadLetterRow(eventId, orderId);

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
        pollForRecordWithKey(paymentEventsTopic, orderId.toString());
    assertThat(republished.value()).contains(eventId.toString());
  }

  @Test
  void aNonAdminCannotReplay() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    seedDeadLetterRow(eventId, orderId);

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
    seedDeadLetterRow(eventId, orderId);

    mockMvc
        .perform(post("/api/v1/admin/dead-letters/" + eventId + "/replay").with(admin()))
        .andExpect(status().isOk());
    mockMvc
        .perform(post("/api/v1/admin/dead-letters/" + eventId + "/replay").with(admin()))
        .andExpect(status().isConflict());
  }

  private void seedDeadLetterRow(UUID eventId, UUID orderId) {
    EventEnvelope envelope =
        new EventEnvelope(
            eventId,
            "PaymentAuthorized",
            1,
            Instant.now(),
            UUID.randomUUID(),
            null,
            orderId,
            "payment-service",
            objectMapper.valueToTree(Map.of()));
    String envelopeJson = objectMapper.writeValueAsString(envelope);
    deadLetterEventRecorder.record(
        envelope, "fulfillment-service.payment-authorized", paymentEventsTopic, envelopeJson);
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
