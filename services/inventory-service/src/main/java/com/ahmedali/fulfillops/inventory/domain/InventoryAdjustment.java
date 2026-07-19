package com.ahmedali.fulfillops.inventory.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit trail for every stock mutation, whatever caused it — an ADMIN adjustment, a
 * reservation, or a release. reasonCode is a plain string rather than one shared Java enum because
 * each source has its own closed set of reasons (AdjustmentReasonCode for admin adjustments,
 * InventoryReleased.v1's own reasonCode enum for releases); the caller is responsible for passing a
 * value from the right set.
 */
@Entity
@Table(name = "inventory_adjustment")
public class InventoryAdjustment {

  @Id private UUID adjustmentId;

  private String sku;

  @Enumerated(EnumType.STRING)
  private AdjustmentSource source;

  private int changeQuantity;
  private int quantityBefore;
  private int quantityAfter;
  private String reasonCode;
  private String reasonDetail;
  private String actor;
  private UUID correlationId;
  private Instant createdAt;

  protected InventoryAdjustment() {
    // JPA
  }

  public InventoryAdjustment(
      UUID adjustmentId,
      String sku,
      AdjustmentSource source,
      int changeQuantity,
      int quantityBefore,
      int quantityAfter,
      String reasonCode,
      String reasonDetail,
      String actor,
      UUID correlationId) {
    this.adjustmentId = adjustmentId;
    this.sku = sku;
    this.source = source;
    this.changeQuantity = changeQuantity;
    this.quantityBefore = quantityBefore;
    this.quantityAfter = quantityAfter;
    this.reasonCode = reasonCode;
    this.reasonDetail = reasonDetail;
    this.actor = actor;
    this.correlationId = correlationId;
    this.createdAt = Instant.now();
  }

  public UUID getAdjustmentId() {
    return adjustmentId;
  }

  public String getSku() {
    return sku;
  }

  public AdjustmentSource getSource() {
    return source;
  }

  public int getChangeQuantity() {
    return changeQuantity;
  }

  public int getQuantityBefore() {
    return quantityBefore;
  }

  public int getQuantityAfter() {
    return quantityAfter;
  }

  public String getReasonCode() {
    return reasonCode;
  }

  public String getReasonDetail() {
    return reasonDetail;
  }

  public String getActor() {
    return actor;
  }

  public UUID getCorrelationId() {
    return correlationId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
