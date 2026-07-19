package com.ahmedali.fulfillops.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Written in the same transaction as the order it resulted from. Its primary key (customerId,
 * idempotencyKey) is what makes a second request with the same key either a safe replay (matching
 * request_fingerprint) or a rejected conflict (see OrderService.createOrder).
 */
@Entity
@Table(name = "idempotency_requests")
public class IdempotencyRequest {

  @EmbeddedId private IdempotencyRequestId id;

  private String requestFingerprint;

  @Column(name = "order_id")
  private UUID orderId;

  private Instant createdAt;

  protected IdempotencyRequest() {
    // JPA
  }

  public IdempotencyRequest(
      UUID customerId, String idempotencyKey, String requestFingerprint, UUID orderId) {
    this.id = new IdempotencyRequestId(customerId, idempotencyKey);
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
