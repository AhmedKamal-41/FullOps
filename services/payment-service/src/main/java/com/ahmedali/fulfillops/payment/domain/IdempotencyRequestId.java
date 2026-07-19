package com.ahmedali.fulfillops.payment.domain;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/**
 * Ownership of an idempotency key is scoped to the actor who sent it, not global — two different
 * operators can each use the key "abc" without colliding.
 */
@Embeddable
public class IdempotencyRequestId implements Serializable {

  private String actorId;
  private String idempotencyKey;

  protected IdempotencyRequestId() {
    // JPA
  }

  public IdempotencyRequestId(String actorId, String idempotencyKey) {
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
    if (!(other instanceof IdempotencyRequestId that)) {
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
