package com.ahmedali.fulfillops.order.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only: one row per action ever taken against an incident, including an OPENED row written
 * automatically when IncidentService creates one — so the full lifecycle is visible from creation,
 * not just from the first operator action.
 */
@Entity
@Table(name = "incident_action_history")
public class IncidentActionHistory {

  @Id private UUID id;

  private UUID incidentId;

  @Enumerated(EnumType.STRING)
  private IncidentActionType action;

  private String actor;
  private String detail;
  private Instant occurredAt;

  protected IncidentActionHistory() {
    // JPA
  }

  public IncidentActionHistory(
      UUID incidentId, IncidentActionType action, String actor, String detail) {
    this.id = UUID.randomUUID();
    this.incidentId = incidentId;
    this.action = action;
    this.actor = actor;
    this.detail = detail;
    this.occurredAt = Instant.now();
  }

  public UUID getIncidentId() {
    return incidentId;
  }

  public IncidentActionType getAction() {
    return action;
  }

  public String getActor() {
    return actor;
  }

  public String getDetail() {
    return detail;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }
}
