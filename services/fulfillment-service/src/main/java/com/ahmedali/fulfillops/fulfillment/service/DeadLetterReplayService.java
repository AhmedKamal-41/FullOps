package com.ahmedali.fulfillops.fulfillment.service;

import com.ahmedali.fulfillops.fulfillment.messaging.DeadLetterEvent;
import com.ahmedali.fulfillops.fulfillment.messaging.DeadLetterEventRepository;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Republishes the exact bytes of a persisted dead-letter record back onto its original topic — the
 * ADMIN caller supplies only an event id, never a payload, so there is no way to inject an
 * arbitrary message through this endpoint. Mirrors OutboxRelay's own shape: publish first, only
 * record success afterward, so a Kafka failure never gets silently recorded as a completed replay.
 */
@Service
public class DeadLetterReplayService {

  private static final Logger log = LoggerFactory.getLogger(DeadLetterReplayService.class);

  private final DeadLetterEventRepository repository;
  private final KafkaTemplate<String, String> kafkaTemplate;

  public DeadLetterReplayService(
      DeadLetterEventRepository repository, KafkaTemplate<String, String> kafkaTemplate) {
    this.repository = repository;
    this.kafkaTemplate = kafkaTemplate;
  }

  public DeadLetterEvent replay(UUID eventId, String actorId) {
    DeadLetterEvent record =
        repository
            .findFirstByIdEventId(eventId)
            .orElseThrow(() -> new DeadLetterEventNotFoundException(eventId));
    if (record.isAlreadyReplayed()) {
      throw new DeadLetterEventAlreadyReplayedException(eventId);
    }

    publish(record);

    record.markReplayed(actorId);
    repository.save(record);
    log.info(
        "ADMIN {} replayed dead-letter event {} (type={}) back onto {}",
        actorId,
        eventId,
        record.getEventType(),
        record.getOriginalTopic());
    return record;
  }

  private void publish(DeadLetterEvent record) {
    try {
      kafkaTemplate
          .send(
              record.getOriginalTopic(),
              record.getAggregateId().toString(),
              record.getEnvelopeJson())
          .get();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while replaying dead-letter event", interrupted);
    } catch (ExecutionException failed) {
      throw new IllegalStateException("failed to replay dead-letter event", failed.getCause());
    }
  }
}
