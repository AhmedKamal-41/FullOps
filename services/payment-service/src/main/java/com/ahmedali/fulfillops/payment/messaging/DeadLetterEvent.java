package com.ahmedali.fulfillops.payment.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A persisted copy of an event that exhausted its retry budget and reached this service's
 * dead-letter topic, kept so an ADMIN can find it by event id and safely replay the exact bytes
 * that failed — see DeadLetterReplayService. Never mutated except to mark it replayed.
 */
@Entity
@Table(name = "dead_letter_event")
public class DeadLetterEvent {

  @EmbeddedId private DeadLetterEventId id;

  private String originalTopic;
  private String eventType;
  private UUID aggregateId;

  @Column(columnDefinition = "TEXT")
  private String envelopeJson;

  @Enumerated(EnumType.STRING)
  private DeadLetterEventStatus status;

  private Instant createdAt;
  private Instant replayedAt;
  private String replayedBy;

  protected DeadLetterEvent() {
    // JPA
  }

  public DeadLetterEvent(
      DeadLetterEventId id,
      String originalTopic,
      String eventType,
      UUID aggregateId,
      String envelopeJson) {
    this.id = id;
    this.originalTopic = originalTopic;
    this.eventType = eventType;
    this.aggregateId = aggregateId;
    this.envelopeJson = envelopeJson;
    this.status = DeadLetterEventStatus.PENDING_REVIEW;
    this.createdAt = Instant.now();
  }

  public void markReplayed(String actorId) {
    this.status = DeadLetterEventStatus.REPLAYED;
    this.replayedAt = Instant.now();
    this.replayedBy = actorId;
  }

  public boolean isAlreadyReplayed() {
    return status == DeadLetterEventStatus.REPLAYED;
  }

  public DeadLetterEventId getId() {
    return id;
  }

  public String getOriginalTopic() {
    return originalTopic;
  }

  public String getEventType() {
    return eventType;
  }

  public UUID getAggregateId() {
    return aggregateId;
  }

  public String getEnvelopeJson() {
    return envelopeJson;
  }

  public DeadLetterEventStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getReplayedAt() {
    return replayedAt;
  }

  public String getReplayedBy() {
    return replayedBy;
  }
}
