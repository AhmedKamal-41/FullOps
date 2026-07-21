package com.ahmedali.fulfillops.order.domain;

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
 * One row per order that has ever entered CANCELLATION_PENDING, tracking which of the three
 * possible compensations (inventory release, payment refund, fulfillment cancellation) this
 * particular order actually needs and which have been confirmed so far. A required flag can flip
 * from false to true later (never the reverse) if Order Service learns about a milestone — an
 * inventory reservation, a payment authorization, a fulfillment assignment — only after the
 * cancellation had already started, which can happen because events from different producers carry
 * no cross-topic ordering guarantee. The order is finalized to CANCELLED only once every required
 * flag is confirmed — see OrderCancellationFinalizer. version is real optimistic locking, not
 * decoration: the three confirmations arrive on three different Kafka topics, each consumed on its
 * own listener thread, so two of them can genuinely race to update this same row — see
 * OrderCancellationTransaction and V3__saga.sql for what goes wrong without it.
 */
@Entity
@Table(name = "order_cancellation")
public class OrderCancellation {

  @Id
  @Column(name = "order_id")
  private UUID orderId;

  private String requestedBy;
  private String reasonDetail;

  @Enumerated(EnumType.STRING)
  private OrderCancellationReasonCode cancellationReasonCode;

  private boolean inventoryReleaseRequired;
  private boolean inventoryReleaseConfirmed;
  private boolean paymentRefundRequired;
  private boolean paymentRefundConfirmed;
  private boolean fulfillmentCancelRequired;
  private boolean fulfillmentCancelConfirmed;

  private Instant requestedAt;
  private Instant resolvedAt;

  @Version private int version;

  protected OrderCancellation() {
    // JPA
  }

  public OrderCancellation(
      UUID orderId,
      String requestedBy,
      String reasonDetail,
      OrderCancellationReasonCode cancellationReasonCode,
      boolean inventoryReleaseRequired,
      boolean paymentRefundRequired,
      boolean fulfillmentCancelRequired) {
    this.orderId = orderId;
    this.requestedBy = requestedBy;
    this.reasonDetail = reasonDetail;
    this.cancellationReasonCode = cancellationReasonCode;
    this.inventoryReleaseRequired = inventoryReleaseRequired;
    this.paymentRefundRequired = paymentRefundRequired;
    this.fulfillmentCancelRequired = fulfillmentCancelRequired;
    this.requestedAt = Instant.now();
  }

  public void requireInventoryRelease() {
    this.inventoryReleaseRequired = true;
  }

  public void requirePaymentRefund() {
    this.paymentRefundRequired = true;
  }

  public void requireFulfillmentCancel() {
    this.fulfillmentCancelRequired = true;
  }

  public void confirmInventoryRelease() {
    this.inventoryReleaseConfirmed = true;
  }

  public void confirmPaymentRefund() {
    this.paymentRefundConfirmed = true;
  }

  public void confirmFulfillmentCancel() {
    this.fulfillmentCancelConfirmed = true;
  }

  public boolean isFullyConfirmed() {
    boolean inventoryDone = !inventoryReleaseRequired || inventoryReleaseConfirmed;
    boolean paymentDone = !paymentRefundRequired || paymentRefundConfirmed;
    boolean fulfillmentDone = !fulfillmentCancelRequired || fulfillmentCancelConfirmed;
    return inventoryDone && paymentDone && fulfillmentDone;
  }

  public void markResolved() {
    this.resolvedAt = Instant.now();
  }

  public boolean isResolved() {
    return resolvedAt != null;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public String getRequestedBy() {
    return requestedBy;
  }

  public String getReasonDetail() {
    return reasonDetail;
  }

  public OrderCancellationReasonCode getCancellationReasonCode() {
    return cancellationReasonCode;
  }

  public boolean isInventoryReleaseRequired() {
    return inventoryReleaseRequired;
  }

  public boolean isPaymentRefundRequired() {
    return paymentRefundRequired;
  }

  public boolean isFulfillmentCancelRequired() {
    return fulfillmentCancelRequired;
  }

  public Instant getRequestedAt() {
    return requestedAt;
  }

  public Instant getResolvedAt() {
    return resolvedAt;
  }
}
