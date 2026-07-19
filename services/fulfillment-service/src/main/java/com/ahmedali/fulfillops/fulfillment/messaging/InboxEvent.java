package com.ahmedali.fulfillops.fulfillment.messaging;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Maps to the inbox_event table from the Phase 2 baseline migration. */
@Entity
@Table(name = "inbox_event")
public class InboxEvent {

  @EmbeddedId private InboxEventId id;

  private String eventType;
  private UUID aggregateId;
  private String state;
  private Instant processedAt;
  private Instant createdAt;

  protected InboxEvent() {
    // JPA
  }

  public InboxEvent(InboxEventId id, String eventType, UUID aggregateId) {
    this.id = id;
    this.eventType = eventType;
    this.aggregateId = aggregateId;
    this.state = "PROCESSED";
    this.processedAt = Instant.now();
    this.createdAt = Instant.now();
  }

  public InboxEventId getId() {
    return id;
  }

  public String getEventType() {
    return eventType;
  }

  public UUID getAggregateId() {
    return aggregateId;
  }

  public String getState() {
    return state;
  }
}
