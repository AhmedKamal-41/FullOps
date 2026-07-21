package com.ahmedali.fulfillops.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Written in the same transaction as the cancellation request it resulted from. Its primary key
 * (actorId, idempotencyKey) is what makes a second request with the same key either a safe replay
 * (matching requestFingerprint) or a rejected conflict — see OrderCancellationService.
 */
@Entity
@Table(name = "cancellation_idempotency_requests")
public class CancellationIdempotencyRequest {

  @EmbeddedId private CancellationIdempotencyRequestId id;

  private String requestFingerprint;

  @Column(name = "order_id")
  private UUID orderId;

  private Instant createdAt;

  protected CancellationIdempotencyRequest() {
    // JPA
  }

  public CancellationIdempotencyRequest(
      String actorId, String idempotencyKey, String requestFingerprint, UUID orderId) {
    this.id = new CancellationIdempotencyRequestId(actorId, idempotencyKey);
    this.requestFingerprint = requestFingerprint;
    this.orderId = orderId;
    this.createdAt = Instant.now();
  }

  public String getRequestFingerprint() {
    return requestFingerprint;
  }

  public UUID getOrderId() {
    return orderId;
  }
}
