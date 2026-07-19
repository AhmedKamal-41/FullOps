package com.ahmedali.fulfillops.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Written in the same transaction as the resource it resulted from. Its primary key (actorId,
 * idempotencyKey) is what makes a second request with the same key either a safe replay (matching
 * request_fingerprint) or a rejected conflict — see ProductService and StockAdjustmentService for
 * how the two cases are told apart.
 */
@Entity
@Table(name = "idempotency_requests")
public class IdempotencyRequest {

  @EmbeddedId private IdempotencyRequestId id;

  private String requestFingerprint;

  @Enumerated(EnumType.STRING)
  private ReferenceType referenceType;

  @Column(name = "reference_id")
  private UUID referenceId;

  private Instant createdAt;

  protected IdempotencyRequest() {
    // JPA
  }

  public IdempotencyRequest(
      String actorId,
      String idempotencyKey,
      String requestFingerprint,
      ReferenceType referenceType,
      UUID referenceId) {
    this.id = new IdempotencyRequestId(actorId, idempotencyKey);
    this.requestFingerprint = requestFingerprint;
    this.referenceType = referenceType;
    this.referenceId = referenceId;
    this.createdAt = Instant.now();
  }

  public String getRequestFingerprint() {
    return requestFingerprint;
  }

  public ReferenceType getReferenceType() {
    return referenceType;
  }

  public UUID getReferenceId() {
    return referenceId;
  }
}
