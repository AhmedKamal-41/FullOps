package com.ahmedali.fulfillops.order.messaging;

import java.time.Instant;
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
  private final String producerName;

  public OutboxEventWriter(
      OutboxEventRepository outboxEventRepository,
      ObjectMapper objectMapper,
      @Value("${spring.application.name}") String producerName) {
    this.outboxEventRepository = outboxEventRepository;
    this.objectMapper = objectMapper;
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
            Instant.now());
    outboxEventRepository.save(event);
    return eventId;
  }
}
