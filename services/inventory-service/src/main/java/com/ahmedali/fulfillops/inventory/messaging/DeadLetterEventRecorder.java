package com.ahmedali.fulfillops.inventory.messaging;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Called from every @DltHandler in this service, so every dead-lettered event is queryable and
 * safely replayable later — not just logged and forgotten. See DeadLetterReplayService for the
 * ADMIN-only replay path this feeds.
 */
@Component
public class DeadLetterEventRecorder {

  private final DeadLetterEventRepository repository;

  public DeadLetterEventRecorder(DeadLetterEventRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public void record(
      EventEnvelope envelope, String consumerName, String originalTopic, String envelopeJson) {
    DeadLetterEventId id = new DeadLetterEventId(envelope.eventId(), consumerName);
    if (repository.existsById(id)) {
      return;
    }
    repository.save(
        new DeadLetterEvent(
            id, originalTopic, envelope.eventType(), envelope.aggregateId(), envelopeJson));
  }
}
