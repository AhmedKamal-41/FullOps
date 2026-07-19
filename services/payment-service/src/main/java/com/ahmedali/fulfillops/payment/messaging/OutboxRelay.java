package com.ahmedali.fulfillops.payment.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Polls outbox_event for due rows and publishes them to Kafka. Claiming (FOR UPDATE SKIP LOCKED)
 * happens in its own short transaction inside OutboxEventRepository; the Kafka send below runs
 * outside any transaction so a slow broker never holds a DB lock open. If this process crashes
 * after Kafka acknowledges but before the row is marked PUBLISHED, the next poll republishes the
 * same event — this is expected (see docs/adr/0004-at-least-once-delivery.md) and is why every
 * consumer must be idempotent, not why this class needs to be cleverer.
 */
@Component
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  private final OutboxEventRepository outboxEventRepository;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final String topic;
  private final int batchSize;
  private final int maxAttempts;
  private final int baseBackoffSeconds;
  private final int maxBackoffSeconds;

  public OutboxRelay(
      OutboxEventRepository outboxEventRepository,
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      @Value("${app.messaging.topic}") String topic,
      @Value("${app.messaging.outbox-batch-size:50}") int batchSize,
      @Value("${app.messaging.outbox-max-attempts:5}") int maxAttempts,
      @Value("${app.messaging.outbox-base-backoff-seconds:2}") int baseBackoffSeconds,
      @Value("${app.messaging.outbox-max-backoff-seconds:60}") int maxBackoffSeconds) {
    this.outboxEventRepository = outboxEventRepository;
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.topic = topic;
    this.batchSize = batchSize;
    this.maxAttempts = maxAttempts;
    this.baseBackoffSeconds = baseBackoffSeconds;
    this.maxBackoffSeconds = maxBackoffSeconds;
  }

  @Scheduled(fixedDelayString = "${app.messaging.outbox-poll-interval-ms:500}")
  public void pollAndPublish() {
    List<OutboxEvent> batch = outboxEventRepository.claimBatch(batchSize);
    for (OutboxEvent event : batch) {
      publishOne(event);
    }
  }

  private void publishOne(OutboxEvent event) {
    MDC.put("correlationId", event.getCorrelationId().toString());
    MDC.put("eventId", event.getEventId().toString());
    try {
      String envelopeJson = toEnvelopeJson(event);
      ProducerRecord<String, String> record =
          new ProducerRecord<>(topic, event.getAggregateId().toString(), envelopeJson);
      addHeader(record, "eventId", event.getEventId().toString());
      addHeader(record, "eventType", event.getEventType());
      addHeader(record, "eventVersion", String.valueOf(event.getEventVersion()));
      addHeader(record, "correlationId", event.getCorrelationId().toString());
      if (event.getCausationId() != null) {
        addHeader(record, "causationId", event.getCausationId().toString());
      }

      // Blocks for the broker's acknowledgement before this method returns, so the row
      // is only marked PUBLISHED once Kafka has actually confirmed the write.
      kafkaTemplate.send(record).get();

      outboxEventRepository.markPublished(event.getEventId(), Instant.now());
      log.info("published outbox event type={}", event.getEventType());
    } catch (Exception e) {
      Instant nextAttemptAt = Instant.now().plusSeconds(backoffSeconds(event.getAttemptCount()));
      outboxEventRepository.markFailedAttempt(
          event.getEventId(), e.getMessage(), nextAttemptAt, maxAttempts);
      log.warn("failed to publish outbox event type={}: {}", event.getEventType(), e.getMessage());
    } finally {
      MDC.remove("correlationId");
      MDC.remove("eventId");
    }
  }

  private String toEnvelopeJson(OutboxEvent event) {
    EventEnvelope envelope =
        new EventEnvelope(
            event.getEventId(),
            event.getEventType(),
            event.getEventVersion(),
            event.getOccurredAt(),
            event.getCorrelationId(),
            event.getCausationId(),
            event.getAggregateId(),
            event.getProducer(),
            objectMapper.readTree(event.getPayload()));
    return objectMapper.writeValueAsString(envelope);
  }

  private int backoffSeconds(int attemptsSoFar) {
    int delay = baseBackoffSeconds * (1 << Math.min(attemptsSoFar, 10));
    return Math.min(delay, maxBackoffSeconds);
  }

  private static void addHeader(ProducerRecord<String, String> record, String key, String value) {
    record.headers().add(new RecordHeader(key, value.getBytes(StandardCharsets.UTF_8)));
  }
}
