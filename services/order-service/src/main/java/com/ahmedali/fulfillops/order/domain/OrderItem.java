package com.ahmedali.fulfillops.order.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "order_items")
public class OrderItem {

  @Id private UUID orderItemId;

  @ManyToOne
  @JoinColumn(name = "order_id")
  private Order order;

  private String sku;
  private int quantity;
  private BigDecimal unitPrice;
  private BigDecimal lineTotal;

  protected OrderItem() {
    // JPA
  }

  public OrderItem(
      UUID orderItemId,
      Order order,
      String sku,
      int quantity,
      BigDecimal unitPrice,
      BigDecimal lineTotal) {
    this.orderItemId = orderItemId;
    this.order = order;
    this.sku = sku;
    this.quantity = quantity;
    this.unitPrice = unitPrice;
    this.lineTotal = lineTotal;
  }

  public String getSku() {
    return sku;
  }

  public int getQuantity() {
    return quantity;
  }

  public BigDecimal getUnitPrice() {
    return unitPrice;
  }

  public BigDecimal getLineTotal() {
    return lineTotal;
  }
}
