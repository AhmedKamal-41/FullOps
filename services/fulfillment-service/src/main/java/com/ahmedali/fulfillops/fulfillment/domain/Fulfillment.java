package com.ahmedali.fulfillops.fulfillment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per order Fulfillment has taken on. orderId is unique at the database level (see
 * V2__fulfillments.sql): PaymentAuthorized.v1 for an order can only ever produce one fulfillment.
 * version is what makes two concurrent operator commands (claim, advance, cancel) against the same
 * fulfillment safe — see FulfillmentTransition.
 */
@Entity
@Table(name = "fulfillments")
public class Fulfillment {

  @Id private UUID fulfillmentId;

  private UUID orderId;

  @Enumerated(EnumType.STRING)
  private FulfillmentStatus status;

  private String warehouseId;
  private String assigneeId;
  private Instant slaDueAt;
  private String trackingReference;
  private Instant deliveredAt;
  private String cancellationReasonCode;
  private String cancellationReasonDetail;

  @Column(name = "correlation_id")
  private UUID correlationId;

  @Version private long version;

  private Instant createdAt;
  private Instant updatedAt;

  protected Fulfillment() {
    // JPA
  }

  public static Fulfillment create(
      UUID fulfillmentId, UUID orderId, String warehouseId, Instant slaDueAt, UUID correlationId) {
    Fulfillment fulfillment = new Fulfillment();
    fulfillment.fulfillmentId = fulfillmentId;
    fulfillment.orderId = orderId;
    fulfillment.status = FulfillmentStatus.ASSIGNED;
    fulfillment.warehouseId = warehouseId;
    fulfillment.slaDueAt = slaDueAt;
    fulfillment.correlationId = correlationId;
    fulfillment.createdAt = Instant.now();
    fulfillment.updatedAt = Instant.now();
    return fulfillment;
  }

  public void claim(String actorId) {
    if (assigneeId != null) {
      throw new FulfillmentAlreadyClaimedException(fulfillmentId, assigneeId);
    }
    this.assigneeId = actorId;
    this.updatedAt = Instant.now();
  }

  public void advanceTo(
      FulfillmentStatus newStatus, String trackingReference, Instant deliveredAt) {
    this.status = newStatus;
    if (trackingReference != null) {
      this.trackingReference = trackingReference;
    }
    if (deliveredAt != null) {
      this.deliveredAt = deliveredAt;
    }
    this.updatedAt = Instant.now();
  }

  public void cancel(String reasonCode, String reasonDetail) {
    this.status = FulfillmentStatus.CANCELLED;
    this.cancellationReasonCode = reasonCode;
    this.cancellationReasonDetail = reasonDetail;
    this.updatedAt = Instant.now();
  }

  public boolean isClaimed() {
    return assigneeId != null;
  }

  public UUID getFulfillmentId() {
    return fulfillmentId;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public FulfillmentStatus getStatus() {
    return status;
  }

  public String getWarehouseId() {
    return warehouseId;
  }

  public String getAssigneeId() {
    return assigneeId;
  }

  public Instant getSlaDueAt() {
    return slaDueAt;
  }

  public String getTrackingReference() {
    return trackingReference;
  }

  public Instant getDeliveredAt() {
    return deliveredAt;
  }

  public String getCancellationReasonCode() {
    return cancellationReasonCode;
  }

  public String getCancellationReasonDetail() {
    return cancellationReasonDetail;
  }

  public UUID getCorrelationId() {
    return correlationId;
  }

  public long getVersion() {
    return version;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
