package com.ahmedali.fulfillops.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Written in the same transaction as the order it resulted from. Its primary key (customerId,
 * idempotencyKey) is what makes a second request with the same key either a safe replay (matching
 * request_fingerprint) or a rejected conflict (see OrderService.createOrder).
 *
 * <p>Implements {@link Persistable} and reports {@code isNew() == true} so a save is always a JPA
 * {@code persist} (a real INSERT), never a {@code merge}. With an assigned {@code @EmbeddedId},
 * Spring Data would otherwise route save() through merge, which SELECTs first and silently turns a
 * would-be duplicate into an UPDATE — so a concurrent loser whose SELECT already sees the winner's
 * committed row never triggers the unique-constraint violation the whole insert-then-catch design
 * in OrderCreationTransaction/OrderService relies on, and its order commits as a duplicate. These
 * rows are only ever created, never updated, so isNew() is unconditionally true.
 */
@Entity
@Table(name = "idempotency_requests")
public class IdempotencyRequest implements Persistable<IdempotencyRequestId> {

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

  @Override
  public IdempotencyRequestId getId() {
    return id;
  }

  @Override
  @Transient
  public boolean isNew() {
    return true;
  }
}
