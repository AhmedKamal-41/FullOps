package com.ahmedali.fulfillops.order.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per (order, stage) the order has ever entered — this state machine never revisits a
 * stage, so (orderId, stage) is unique (enforced in V4__operations.sql). duration_seconds is
 * computed once, on exit, so percentile queries never recompute it from timestamps at read time.
 */
@Entity
@Table(name = "order_stage_duration")
public class OrderStageDuration {

  @Id private UUID id;

  private UUID orderId;

  @Enumerated(EnumType.STRING)
  private OrderStatus stage;

  private Instant enteredAt;
  private Instant exitedAt;
  private Long durationSeconds;

  protected OrderStageDuration() {
    // JPA
  }

  public OrderStageDuration(UUID orderId, OrderStatus stage, Instant enteredAt) {
    this.id = UUID.randomUUID();
    this.orderId = orderId;
    this.stage = stage;
    this.enteredAt = enteredAt;
  }

  public void close(Instant exitedAt) {
    this.exitedAt = exitedAt;
    this.durationSeconds = Duration.between(enteredAt, exitedAt).getSeconds();
  }

  public UUID getOrderId() {
    return orderId;
  }

  public OrderStatus getStage() {
    return stage;
  }

  public Instant getEnteredAt() {
    return enteredAt;
  }

  public Instant getExitedAt() {
    return exitedAt;
  }

  public Long getDurationSeconds() {
    return durationSeconds;
  }
}
