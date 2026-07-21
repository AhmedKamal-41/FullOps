package com.ahmedali.fulfillops.order.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A human-facing exception raised against an order: something Order Service could not safely
 * auto-resolve. At most one OPEN incident of a given kind can exist per order at once — enforced by
 * a partial unique index in V3__saga.sql, not just application logic — which is what makes
 * IncidentService.openOrDeduplicate idempotent under retries and concurrent detection.
 */
@Entity
@Table(name = "operations_incident")
public class OperationsIncident {

  @Id private UUID incidentId;

  private UUID orderId;

  @Enumerated(EnumType.STRING)
  private IncidentKind kind;

  private String detail;

  @Enumerated(EnumType.STRING)
  private IncidentStatus status;

  private Instant createdAt;
  private Instant resolvedAt;

  private Instant acknowledgedAt;
  private String acknowledgedBy;
  private String assignedTo;
  private Instant assignedAt;
  private String resolvedBy;
  private String resolutionNote;

  protected OperationsIncident() {
    // JPA
  }

  public OperationsIncident(UUID orderId, IncidentKind kind, String detail) {
    this.incidentId = UUID.randomUUID();
    this.orderId = orderId;
    this.kind = kind;
    this.detail = detail;
    this.status = IncidentStatus.OPEN;
    this.createdAt = Instant.now();
  }

  public UUID getIncidentId() {
    return incidentId;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public IncidentKind getKind() {
    return kind;
  }

  public String getDetail() {
    return detail;
  }

  public IncidentStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getResolvedAt() {
    return resolvedAt;
  }

  /**
   * Callers (IncidentActionService) are responsible for checking the incident isn't already
   * RESOLVED first — this method only applies the change, matching every other *Transaction in this
   * service.
   */
  public void acknowledge(String actor) {
    this.status = IncidentStatus.ACKNOWLEDGED;
    this.acknowledgedAt = Instant.now();
    this.acknowledgedBy = actor;
  }

  public void assign(String assignee) {
    this.assignedTo = assignee;
    this.assignedAt = Instant.now();
  }

  public void resolve(String actor, String resolutionNote) {
    this.status = IncidentStatus.RESOLVED;
    this.resolvedAt = Instant.now();
    this.resolvedBy = actor;
    this.resolutionNote = resolutionNote;
  }

  public Instant getAcknowledgedAt() {
    return acknowledgedAt;
  }

  public String getAcknowledgedBy() {
    return acknowledgedBy;
  }

  public String getAssignedTo() {
    return assignedTo;
  }

  public Instant getAssignedAt() {
    return assignedAt;
  }

  public String getResolvedBy() {
    return resolvedBy;
  }

  public String getResolutionNote() {
    return resolutionNote;
  }
}
