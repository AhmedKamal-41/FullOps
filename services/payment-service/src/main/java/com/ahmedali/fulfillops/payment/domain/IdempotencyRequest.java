package com.ahmedali.fulfillops.payment.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Written in the same transaction as the refund it resulted from. Its primary key (actorId,
 * idempotencyKey) is what makes a second request with the same key either a safe replay (matching
 * requestFingerprint) or a rejected conflict — see RefundService.
 */
@Entity
@Table(name = "idempotency_requests")
public class IdempotencyRequest {

  @EmbeddedId private IdempotencyRequestId id;

  private String requestFingerprint;
  private UUID refundId;
  private Instant createdAt;

  protected IdempotencyRequest() {
    // JPA
  }

  public IdempotencyRequest(
      String actorId, String idempotencyKey, String requestFingerprint, UUID refundId) {
    this.id = new IdempotencyRequestId(actorId, idempotencyKey);
    this.requestFingerprint = requestFingerprint;
    this.refundId = refundId;
    this.createdAt = Instant.now();
  }

  public String getRequestFingerprint() {
    return requestFingerprint;
  }

  public UUID getRefundId() {
    return refundId;
  }
}
