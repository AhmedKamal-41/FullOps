package com.ahmedali.fulfillops.inventory.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * available_quantity + reserved_quantity is the total stock on hand for the SKU. version is the
 * optimistic lock every mutation goes through: two concurrent updates to the same row can't both
 * succeed, so whichever loses gets an ObjectOptimisticLockingFailureException at flush time — see
 * ReservationTransaction and StockAdjustmentTransaction for how that's caught and retried.
 */
@Entity
@Table(name = "stock_level")
public class StockLevel {

  @Id private UUID stockLevelId;

  private String sku;
  private int availableQuantity;
  private int reservedQuantity;

  @Version private long version;

  private Instant updatedAt;

  protected StockLevel() {
    // JPA
  }

  public StockLevel(UUID stockLevelId, String sku) {
    this.stockLevelId = stockLevelId;
    this.sku = sku;
    this.availableQuantity = 0;
    this.reservedQuantity = 0;
    this.updatedAt = Instant.now();
  }

  public boolean hasAvailable(int quantity) {
    return availableQuantity >= quantity;
  }

  public void reserve(int quantity) {
    if (quantity > availableQuantity) {
      throw new IllegalStateException(
          "cannot reserve "
              + quantity
              + " of "
              + sku
              + ", only "
              + availableQuantity
              + " available");
    }
    availableQuantity -= quantity;
    reservedQuantity += quantity;
    updatedAt = Instant.now();
  }

  public void release(int quantity) {
    reservedQuantity -= quantity;
    availableQuantity += quantity;
    updatedAt = Instant.now();
  }

  public void adjust(int changeQuantity) {
    if (availableQuantity + changeQuantity < 0) {
      throw new IllegalStateException(
          "adjustment of "
              + changeQuantity
              + " would leave "
              + sku
              + " with negative available quantity");
    }
    availableQuantity += changeQuantity;
    updatedAt = Instant.now();
  }

  public UUID getStockLevelId() {
    return stockLevelId;
  }

  public String getSku() {
    return sku;
  }

  public int getAvailableQuantity() {
    return availableQuantity;
  }

  public int getReservedQuantity() {
    return reservedQuantity;
  }

  public long getVersion() {
    return version;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
