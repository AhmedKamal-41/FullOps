package com.ahmedali.fulfillops.payment.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps to the outbox_event table from the Phase 2 baseline migration. A row here and the domain
 * change it describes are written in the same local transaction (see OutboxEventWriter); a separate
 * OutboxRelay poller is what actually reaches Kafka.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

  @Id private UUID eventId;

  private String eventType;
  private int eventVersion;
  private UUID aggregateId;
  private UUID correlationId;
  private UUID causationId;
  private String producer;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private String payload;

  private Instant occurredAt;
  private String state;
  private int attemptCount;
  private Instant nextAttemptAt;
  private String lastError;
  private Instant publishedAt;
  private Instant createdAt;

  protected OutboxEvent() {
    // JPA
  }

  public OutboxEvent(
      UUID eventId,
      String eventType,
      int eventVersion,
      UUID aggregateId,
      UUID correlationId,
      UUID causationId,
      String producer,
      String payload,
      Instant occurredAt) {
    this.eventId = eventId;
    this.eventType = eventType;
    this.eventVersion = eventVersion;
    this.aggregateId = aggregateId;
    this.correlationId = correlationId;
    this.causationId = causationId;
    this.producer = producer;
    this.payload = payload;
    this.occurredAt = occurredAt;
    this.state = OutboxState.PENDING.name();
    this.attemptCount = 0;
    this.createdAt = Instant.now();
  }

  public UUID getEventId() {
    return eventId;
  }

  public String getEventType() {
    return eventType;
  }

  public int getEventVersion() {
    return eventVersion;
  }

  public UUID getAggregateId() {
    return aggregateId;
  }

  public UUID getCorrelationId() {
    return correlationId;
  }

  public UUID getCausationId() {
    return causationId;
  }

  public String getProducer() {
    return producer;
  }

  public String getPayload() {
    return payload;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public String getState() {
    return state;
  }

  public int getAttemptCount() {
    return attemptCount;
  }
}
