package com.ahmedali.fulfillops.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per refund. payment_id is unique at the database level: a payment refunds at most once.
 */
@Entity
@Table(name = "refunds")
public class Refund {

  @Id private UUID refundId;

  private UUID paymentId;
  private BigDecimal amount;

  @Column(name = "currency_code")
  private String currencyCode;

  private String reasonCode;
  private UUID correlationId;
  private Instant createdAt;

  protected Refund() {
    // JPA
  }

  public Refund(
      UUID refundId,
      UUID paymentId,
      BigDecimal amount,
      String currencyCode,
      String reasonCode,
      UUID correlationId) {
    this.refundId = refundId;
    this.paymentId = paymentId;
    this.amount = amount;
    this.currencyCode = currencyCode;
    this.reasonCode = reasonCode;
    this.correlationId = correlationId;
    this.createdAt = Instant.now();
  }

  public UUID getRefundId() {
    return refundId;
  }

  public UUID getPaymentId() {
    return paymentId;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getCurrencyCode() {
    return currencyCode;
  }

  public String getReasonCode() {
    return reasonCode;
  }

  public UUID getCorrelationId() {
    return correlationId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
