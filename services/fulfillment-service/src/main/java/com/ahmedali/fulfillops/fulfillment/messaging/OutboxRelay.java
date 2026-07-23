package com.ahmedali.fulfillops.fulfillment.messaging;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * same event — this is expected (see docs/ARCHITECTURE.md) and is why every consumer must be
 * idempotent, not why this class needs to be cleverer.
 */
@Component
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  private final OutboxEventRepository outboxEventRepository;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final Tracer tracer;
  private final Propagator propagator;
  private final String topic;
  private final int batchSize;
  private final int maxAttempts;
  private final int baseBackoffSeconds;
  private final int maxBackoffSeconds;

  public OutboxRelay(
      OutboxEventRepository outboxEventRepository,
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      Tracer tracer,
      Propagator propagator,
      @Value("${app.messaging.topic}") String topic,
      @Value("${app.messaging.outbox-batch-size:50}") int batchSize,
      @Value("${app.messaging.outbox-max-attempts:5}") int maxAttempts,
      @Value("${app.messaging.outbox-base-backoff-seconds:2}") int baseBackoffSeconds,
      @Value("${app.messaging.outbox-max-backoff-seconds:60}") int maxBackoffSeconds) {
    this.outboxEventRepository = outboxEventRepository;
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.tracer = tracer;
    this.propagator = propagator;
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

      // This method runs on the scheduler's own thread, disconnected from whatever request or
      // Kafka-consume thread originally wrote this outbox row — without resuming that thread's
      // trace here, every publish would start a brand new, disconnected trace instead of
      // continuing the one the caller was already in.
      Span resumedSpan = resumeSpan(event.getTraceContext());
      if (resumedSpan == null) {
        // Blocks for the broker's acknowledgement before this method returns, so the row
        // is only marked PUBLISHED once Kafka has actually confirmed the write.
        kafkaTemplate.send(record).get();
      } else {
        try (Tracer.SpanInScope scope = tracer.withSpan(resumedSpan)) {
          kafkaTemplate.send(record).get();
        } finally {
          resumedSpan.end();
        }
      }

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

  /**
   * Rebuilds the span OutboxEventWriter captured at write time, as a starting point to publish from
   * — not the original span itself (that request or Kafka-consume thread is long gone), but a new
   * one whose parent is that original context, so it shares the same trace ID. Returns null when
   * the row was written with nothing being traced (see OutboxEventWriter.captureTraceContext).
   */
  @SuppressWarnings("unchecked")
  private Span resumeSpan(String traceContextJson) {
    if (traceContextJson == null) {
      return null;
    }
    Map<String, String> carrier =
        (Map<String, String>) objectMapper.readValue(traceContextJson, Map.class);
    return propagator.extract(carrier, Map::get).name("outbox-relay-publish").start();
  }

  private int backoffSeconds(int attemptsSoFar) {
    int delay = baseBackoffSeconds * (1 << Math.min(attemptsSoFar, 10));
    return Math.min(delay, maxBackoffSeconds);
  }

  private static void addHeader(ProducerRecord<String, String> record, String key, String value) {
    record.headers().add(new RecordHeader(key, value.getBytes(StandardCharsets.UTF_8)));
  }
}
