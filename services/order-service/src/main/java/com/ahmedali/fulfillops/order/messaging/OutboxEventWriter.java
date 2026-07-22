package com.ahmedali.fulfillops.order.messaging;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Domain code calls write(...) inside its own @Transactional method, alongside its own repository
 * save, so the domain change and the fact that it happened commit together or not at all. Nothing
 * here talks to Kafka — OutboxRelay does that separately.
 */
@Service
public class OutboxEventWriter {

  private final OutboxEventRepository outboxEventRepository;
  private final ObjectMapper objectMapper;
  private final Tracer tracer;
  private final Propagator propagator;
  private final String producerName;

  public OutboxEventWriter(
      OutboxEventRepository outboxEventRepository,
      ObjectMapper objectMapper,
      Tracer tracer,
      Propagator propagator,
      @Value("${spring.application.name}") String producerName) {
    this.outboxEventRepository = outboxEventRepository;
    this.objectMapper = objectMapper;
    this.tracer = tracer;
    this.propagator = propagator;
    this.producerName = producerName;
  }

  public UUID write(
      String eventType,
      int eventVersion,
      UUID aggregateId,
      UUID correlationId,
      UUID causationId,
      Object payload) {
    UUID eventId = UUID.randomUUID();
    String payloadJson = objectMapper.writeValueAsString(payload);
    OutboxEvent event =
        new OutboxEvent(
            eventId,
            eventType,
            eventVersion,
            aggregateId,
            correlationId,
            causationId,
            producerName,
            payloadJson,
            Instant.now(),
            captureTraceContext());
    outboxEventRepository.save(event);
    return eventId;
  }

  /**
   * Captures the W3C trace context active right now (the caller's own request or Kafka-consume
   * span) so OutboxRelay can resume it later on its own scheduler thread — see OutboxRelay's
   * publishOne(). Returns null when nothing is being traced (context is unset, or tracing is
   * disabled, e.g. in tests).
   */
  private String captureTraceContext() {
    var currentContext = tracer.currentTraceContext().context();
    if (currentContext == null) {
      return null;
    }
    Map<String, String> carrier = new HashMap<>();
    propagator.inject(currentContext, carrier, Map::put);
    return carrier.isEmpty() ? null : objectMapper.writeValueAsString(carrier);
  }
}
