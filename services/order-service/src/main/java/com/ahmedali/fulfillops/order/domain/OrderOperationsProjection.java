package com.ahmedali.fulfillops.order.domain;

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
 * A denormalized, ops-facing read model of one order — status, stage timing, and every reason code
 * an operator would filter or search by, kept in one row so the work queue and KPI reads never have
 * to join orders/order_status_history/order_cancellation/operations_incident. Always derivable from
 * this service's own durable tables — see OperationsProjectionRebuildService.
 *
 * <p>version is an optimistic lock: three independent Kafka listener threads (inventory/payment/
 * fulfillment topics) can race to update the same order's projection row during cancellation, the
 * same lost-update risk Phase 8 hit once for OrderCancellation — see PHASE_STATUS.md's Phase 8
 * section. Applied here proactively rather than waiting to rediscover it.
 */
@Entity
@Table(name = "order_operations_projection")
public class OrderOperationsProjection {

  @Id
  @Column(name = "order_id")
  private UUID orderId;

  private UUID customerId;

  @Enumerated(EnumType.STRING)
  private OrderStatus status;

  private String currencyCode;
  private BigDecimal totalAmount;
  private Instant createdAt;
  private Instant currentStageEnteredAt;
  private Instant updatedAt;

  private String inventoryRejectionReasonCode;
  private String paymentDeclineReasonCode;
  private int paymentTechnicalFailureCount;
  private String cancellationReasonCode;
  private String requiresReviewReasonCode;
  private int openIncidentCount;

  @Version private int version;

  protected OrderOperationsProjection() {
    // JPA
  }

  public OrderOperationsProjection(
      UUID orderId,
      UUID customerId,
      OrderStatus status,
      String currencyCode,
      BigDecimal totalAmount,
      Instant createdAt) {
    this.orderId = orderId;
    this.customerId = customerId;
    this.status = status;
    this.currencyCode = currencyCode;
    this.totalAmount = totalAmount;
    this.createdAt = createdAt;
    this.currentStageEnteredAt = createdAt;
    this.updatedAt = createdAt;
  }

  /**
   * reasonCode is nullable and means different things depending on newStatus: for
   * CANCELLATION_PENDING/CANCELLED it sets cancellationReasonCode, for REQUIRES_REVIEW it sets
   * requiresReviewReasonCode, otherwise it's ignored — the same one-column-many-meanings shape
   * order_status_history.reason_code already uses.
   */
  public void advanceStage(OrderStatus newStatus, String reasonCode, Instant now) {
    this.status = newStatus;
    this.currentStageEnteredAt = now;
    this.updatedAt = now;
    if (reasonCode == null) {
      return;
    }
    if (newStatus == OrderStatus.CANCELLATION_PENDING || newStatus == OrderStatus.CANCELLED) {
      this.cancellationReasonCode = reasonCode;
    } else if (newStatus == OrderStatus.REQUIRES_REVIEW) {
      this.requiresReviewReasonCode = reasonCode;
    }
  }

  public void recordInventoryRejection(String reasonCode) {
    this.inventoryRejectionReasonCode = reasonCode;
  }

  public void recordPaymentOutcome(String declineReasonCode, int precedingTechnicalFailureCount) {
    this.paymentDeclineReasonCode = declineReasonCode;
    this.paymentTechnicalFailureCount = precedingTechnicalFailureCount;
  }

  public void setOpenIncidentCount(int openIncidentCount) {
    this.openIncidentCount = openIncidentCount;
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

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getCurrentStageEnteredAt() {
    return currentStageEnteredAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
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

  public String getCancellationReasonCode() {
    return cancellationReasonCode;
  }

  public String getRequiresReviewReasonCode() {
    return requiresReviewReasonCode;
  }

  public int getOpenIncidentCount() {
    return openIncidentCount;
  }

  public int getVersion() {
    return version;
  }
}
