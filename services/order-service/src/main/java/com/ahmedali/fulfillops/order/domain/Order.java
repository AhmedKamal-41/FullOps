package com.ahmedali.fulfillops.order.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The version column is an optimistic lock: order creation never updates an order after the fact,
 * but later lifecycle changes that do (inventory reserved, payment authorized, ...) will, and this
 * is what stops two concurrent updates from silently overwriting each other.
 */
@Entity
@Table(name = "orders")
public class Order {

  @Id private UUID orderId;

  private UUID customerId;

  @Enumerated(EnumType.STRING)
  private OrderStatus status;

  private String currencyCode;
  private BigDecimal totalAmount;

  // These three only ever arrive in a Kafka event payload — see V4__operations.sql for why
  // they're durably stored on orders itself rather than only in order_operations_projection.
  private String inventoryRejectionReasonCode;
  private String paymentDeclineReasonCode;
  private int paymentTechnicalFailureCount;

  // Nullable — see V5__order_correlation_id.sql for why an order placed before that migration
  // genuinely has none recorded, rather than a backfilled fake value.
  private UUID correlationId;

  @Version private int version;

  private Instant createdAt;
  private Instant updatedAt;

  @OneToMany(
      mappedBy = "order",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.EAGER)
  private List<OrderItem> items = new ArrayList<>();

  protected Order() {
    // JPA
  }

  public Order(UUID orderId, UUID customerId, String currencyCode, BigDecimal totalAmount) {
    this.orderId = orderId;
    this.customerId = customerId;
    this.status = OrderStatus.PENDING;
    this.currencyCode = currencyCode;
    this.totalAmount = totalAmount;
    this.createdAt = Instant.now();
    this.updatedAt = Instant.now();
  }

  public void addItem(String sku, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
    items.add(new OrderItem(UUID.randomUUID(), this, sku, quantity, unitPrice, lineTotal));
  }

  /**
   * Callers are responsible for checking OrderStatusTransitions.isAllowed(status, newStatus) first
   * — this method only applies the change, it doesn't validate it.
   */
  public void updateStatus(OrderStatus newStatus) {
    this.status = newStatus;
    this.updatedAt = Instant.now();
  }

  public void recordInventoryRejection(String reasonCode) {
    this.inventoryRejectionReasonCode = reasonCode;
  }

  public void recordPaymentOutcome(String declineReasonCode, int precedingTechnicalFailureCount) {
    this.paymentDeclineReasonCode = declineReasonCode;
    this.paymentTechnicalFailureCount = precedingTechnicalFailureCount;
  }

  public void recordCorrelationId(UUID correlationId) {
    this.correlationId = correlationId;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public OrderStatus getStatus() {
    return status;
  }

  public String getCurrencyCode() {
    return currencyCode;
  }

  public BigDecimal getTotalAmount() {
    return totalAmount;
  }

  public int getVersion() {
    return version;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public List<OrderItem> getItems() {
    return items;
  }

  public String getInventoryRejectionReasonCode() {
    return inventoryRejectionReasonCode;
  }

  public String getPaymentDeclineReasonCode() {
    return paymentDeclineReasonCode;
  }

  public int getPaymentTechnicalFailureCount() {
    return paymentTechnicalFailureCount;
  }

  public UUID getCorrelationId() {
    return correlationId;
  }
}
