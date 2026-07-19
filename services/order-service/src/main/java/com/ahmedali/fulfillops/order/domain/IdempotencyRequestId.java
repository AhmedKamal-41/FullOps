package com.ahmedali.fulfillops.order.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Ownership of an idempotency key is scoped to the customer who sent it, not global — two different
 * customers can each use the key "abc" without colliding.
 */
@Embeddable
public class IdempotencyRequestId implements Serializable {

  private UUID customerId;
  private String idempotencyKey;

  protected IdempotencyRequestId() {
    // JPA
  }

  public IdempotencyRequestId(UUID customerId, String idempotencyKey) {
    this.customerId = customerId;
    this.idempotencyKey = idempotencyKey;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof IdempotencyRequestId that)) {
      return false;
    }
    return Objects.equals(customerId, that.customerId)
        && Objects.equals(idempotencyKey, that.idempotencyKey);
  }

  @Override
  public int hashCode() {
    return Objects.hash(customerId, idempotencyKey);
  }
}
