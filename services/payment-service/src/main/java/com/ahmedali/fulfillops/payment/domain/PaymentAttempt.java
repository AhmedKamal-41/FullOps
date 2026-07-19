package com.ahmedali.fulfillops.payment.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per attempt made against the provider for an order, including attempts that never produce
 * a Payment row because every one of them failed. See PaymentAttemptRecorder for why these are
 * written in their own transaction, independent of the outer authorization flow's outcome.
 */
@Entity
@Table(name = "payment_attempts")
public class PaymentAttempt {

  @Id private UUID attemptId;

  private UUID orderId;
  private int attemptNumber;

  @Enumerated(EnumType.STRING)
  private PaymentAttemptOutcome outcome;

  private String detail;
  private Instant createdAt;

  protected PaymentAttempt() {
    // JPA
  }

  public PaymentAttempt(
      UUID attemptId,
      UUID orderId,
      int attemptNumber,
      PaymentAttemptOutcome outcome,
      String detail) {
    this.attemptId = attemptId;
    this.orderId = orderId;
    this.attemptNumber = attemptNumber;
    this.outcome = outcome;
    this.detail = detail;
    this.createdAt = Instant.now();
  }

  public UUID getAttemptId() {
    return attemptId;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public int getAttemptNumber() {
    return attemptNumber;
  }

  public PaymentAttemptOutcome getOutcome() {
    return outcome;
  }

  public String getDetail() {
    return detail;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
