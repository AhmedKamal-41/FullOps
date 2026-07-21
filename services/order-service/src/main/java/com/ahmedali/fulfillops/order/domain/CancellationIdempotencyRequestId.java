package com.ahmedali.fulfillops.order.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/**
 * Ownership of a cancellation-request idempotency key is scoped to the acting principal (a customer
 * cancelling their own order, or an operator/admin cancelling any order) — not global, and not the
 * order's own customerId, since staff acting on someone else's order needs its own scope.
 */
@Embeddable
public class CancellationIdempotencyRequestId implements Serializable {

  private String actorId;
  private String idempotencyKey;

  protected CancellationIdempotencyRequestId() {
    // JPA
  }

  public CancellationIdempotencyRequestId(String actorId, String idempotencyKey) {
    this.actorId = actorId;
    this.idempotencyKey = idempotencyKey;
  }

  public String getActorId() {
    return actorId;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof CancellationIdempotencyRequestId that)) {
      return false;
    }
    return Objects.equals(actorId, that.actorId)
        && Objects.equals(idempotencyKey, that.idempotencyKey);
  }

  @Override
  public int hashCode() {
    return Objects.hash(actorId, idempotencyKey);
  }
}
