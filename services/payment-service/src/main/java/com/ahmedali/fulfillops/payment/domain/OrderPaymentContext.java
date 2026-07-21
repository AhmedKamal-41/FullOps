package com.ahmedali.fulfillops.payment.domain;

import jakarta.persistence.Colum
n;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Payment Service's own local copy of the order facts it needs, built by consuming order-service's
 * OrderPlaced.v1 — never by reading order-service's database. Deliberately holds only order id,
 * customer id, currency, and amount, per CLAUDE.md's restriction on what order events (and anything
 * derived from them) may carry.
 */
@Entity
@Table(name = "order_payment_context")
public class OrderPaymentContext {

  @Id private UUID orderId;

  private UUID customerId;
  private BigDecimal amount;

  @Column(name = "currency_code")
  private String currencyCode;

  private UUID correlationId;
  private Instant createdAt;

  protected OrderPaymentContext() {
    // JPA
  }

  public OrderPaymentContext(
      UUID orderId, UUID customerId, BigDecimal amount, String currencyCode, UUID correlationId) {
    this.orderId = orderId;
    this.customerId = customerId;
    this.amount = amount;
    this.currencyCode = currencyCode;
    this.correlationId = correlationId;
    this.createdAt = Instant.now();
  }

  public UUID getOrderId() {
    return orderId;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getCurrencyCode() {
    return currencyCode;
  }

  public UUID getCorrelationId() {
    return correlationId;
  }
}
