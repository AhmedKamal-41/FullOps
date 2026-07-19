package com.ahmedali.fulfillops.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per order Payment has decided on. orderId is unique at the database level (see
 * V2__payments.sql): at most one non-refunded payment can ever exist for an order. version is what
 * makes two concurrent refund attempts for the same payment safe, the same way it does for
 * InventoryReservation in inventory-service.
 */
@Entity
@Table(name = "payments")
public class Payment {

  @Id private UUID paymentId;

  private UUID orderId;
  private UUID customerId;
  private BigDecimal amount;

  @Column(name = "currency_code")
  private String currencyCode;

  @Enumerated(EnumType.STRING)
  private PaymentStatus status;

  private String declineReasonCode;
  private String declineReasonDetail;
  private UUID correlationId;

  @Version private long version;

  private Instant createdAt;
  private Instant updatedAt;

  protected Payment() {
    // JPA
  }

  public static Payment authorized(
      UUID paymentId,
      UUID orderId,
      UUID customerId,
      BigDecimal amount,
      String currencyCode,
      UUID correlationId) {
    Payment payment = new Payment();
    payment.paymentId = paymentId;
    payment.orderId = orderId;
    payment.customerId = customerId;
    payment.amount = amount;
    payment.currencyCode = currencyCode;
    payment.status = PaymentStatus.AUTHORIZED;
    payment.correlationId = correlationId;
    payment.createdAt = Instant.now();
    payment.updatedAt = Instant.now();
    return payment;
  }

  public static Payment declined(
      UUID paymentId,
      UUID orderId,
      UUID customerId,
      BigDecimal amount,
      String currencyCode,
      String declineReasonCode,
      String declineReasonDetail,
      UUID correlationId) {
    Payment payment = new Payment();
    payment.paymentId = paymentId;
    payment.orderId = orderId;
    payment.customerId = customerId;
    payment.amount = amount;
    payment.currencyCode = currencyCode;
    payment.status = PaymentStatus.DECLINED;
    payment.declineReasonCode = declineReasonCode;
    payment.declineReasonDetail = declineReasonDetail;
    payment.correlationId = correlationId;
    payment.createdAt = Instant.now();
    payment.updatedAt = Instant.now();
    return payment;
  }

  public boolean isAuthorized() {
    return status == PaymentStatus.AUTHORIZED;
  }

  public void markRefunded() {
    this.status = PaymentStatus.REFUNDED;
    this.updatedAt = Instant.now();
  }

  public UUID getPaymentId() {
    return paymentId;
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

  public PaymentStatus getStatus() {
    return status;
  }

  public String getDeclineReasonCode() {
    return declineReasonCode;
  }

  public String getDeclineReasonDetail() {
    return declineReasonDetail;
  }

  public UUID getCorrelationId() {
    return correlationId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
