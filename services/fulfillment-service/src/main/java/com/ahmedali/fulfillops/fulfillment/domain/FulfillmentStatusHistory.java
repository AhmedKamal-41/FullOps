package com.ahmedali.fulfillops.fulfillment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only: one row per status the fulfillment has ever been in, oldest first. Written in the
 * same transaction as the status change it records — see FulfillmentTransition.
 */
@Entity
@Table(name = "fulfillment_status_history")
public class FulfillmentStatusHistory {

  @Id private UUID fulfillmentStatusHistoryId;

  @Column(name = "fulfillment_id")
  private UUID fulfillmentId;

  @Enumerated(EnumType.STRING)
  private FulfillmentStatus status;

  private String actor;
  private String notes;
  private Instant occurredAt;

  protected FulfillmentStatusHistory() {
    // JPA
  }

  public FulfillmentStatusHistory(
      UUID fulfillmentId, FulfillmentStatus status, String actor, String notes) {
    this.fulfillmentStatusHistoryId = UUID.randomUUID();
    this.fulfillmentId = fulfillmentId;
    this.status = status;
    this.actor = actor;
    this.notes = notes;
    this.occurredAt = Instant.now();
  }

  public FulfillmentStatus getStatus() {
    return status;
  }

  public String getActor() {
    return actor;
  }

  public String getNotes() {
    return notes;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }
}
