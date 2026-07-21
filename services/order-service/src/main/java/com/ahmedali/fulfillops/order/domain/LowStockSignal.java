package com.ahmedali.fulfillops.order.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The latest known low-stock state for one SKU, upserted on every InventoryLowStock.v1 — not
 * order-scoped history, so it's excluded from projection rebuild (see
 * OperationsProjectionRebuildService); it naturally catches up as new events arrive.
 */
@Entity
@Table(name = "low_stock_signal")
public class LowStockSignal {

  @Id private String sku;

  private int availableQuantity;
  private int threshold;
  private boolean belowThreshold;
  private Instant occurredAt;

  protected LowStockSignal() {
    // JPA
  }

  public LowStockSignal(
      String sku,
      int availableQuantity,
      int threshold,
      boolean belowThreshold,
      Instant occurredAt) {
    this.sku = sku;
    this.availableQuantity = availableQuantity;
    this.threshold = threshold;
    this.belowThreshold = belowThreshold;
    this.occurredAt = occurredAt;
  }

  public void update(
      int availableQuantity, int threshold, boolean belowThreshold, Instant occurredAt) {
    this.availableQuantity = availableQuantity;
    this.threshold = threshold;
    this.belowThreshold = belowThreshold;
    this.occurredAt = occurredAt;
  }

  public String getSku() {
    return sku;
  }

  public int getAvailableQuantity() {
    return availableQuantity;
  }

  public int getThreshold() {
    return threshold;
  }

  public boolean isBelowThreshold() {
    return belowThreshold;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }
}
