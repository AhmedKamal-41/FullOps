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
 * The version column is an optimistic lock: Phase 4 never updates an order after creation, but
 * every later phase that does (inventory reserved, payment authorized, ...) will, and this is what
 * stops two concurrent updates from silently overwriting each other.
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

  public List<OrderItem> getItems() {
    return items;
  }
}
