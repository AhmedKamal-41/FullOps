package com.ahmedali.fulfillops.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Append-only: one row per status the order has ever been in, oldest first. */
@Entity
@Table(name = "order_status_history")
public class OrderStatusHistory {

  @Id private UUID orderStatusHistoryId;

  @Column(name = "order_id")
  private UUID orderId;

  @Enumerated(EnumType.STRING)
  private OrderStatus status;

  private String reasonCode;
  private Instant occurredAt;

  protected OrderStatusHistory() {
    // JPA
  }

  public OrderStatusHistory(UUID orderId, OrderStatus status, String reasonCode) {
    this.orderStatusHistoryId = UUID.randomUUID();
    this.orderId = orderId;
    this.status = status;
    this.reasonCode = reasonCode;
    this.occurredAt = Instant.now();
  }

  public OrderStatus getStatus() {
    return status;
  }

  public String getReasonCode() {
    return reasonCode;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }
}
