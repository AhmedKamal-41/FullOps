package com.ahmedali.fulfillops.inventory.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per order Inventory has reserved stock for. version is what makes release idempotent
 * under concurrent release attempts, not just sequential ones: two callers racing to release the
 * same reservation can't both observe RESERVED and both flip it — the loser's flush fails
 * optimistically, retries, and re-reads RELEASED (see ReservationReleaseTransaction).
 */
@Entity
@Table(name = "inventory_reservation")
public class InventoryReservation {

  @Id private UUID reservationId;

  private UUID orderId;

  @Enumerated(EnumType.STRING)
  private ReservationStatus status;

  @Version private long version;

  private Instant createdAt;
  private Instant updatedAt;

  protected InventoryReservation() {
    // JPA
  }

  public InventoryReservation(UUID reservationId, UUID orderId) {
    this.reservationId = reservationId;
    this.orderId = orderId;
    this.status = ReservationStatus.RESERVED;
    this.createdAt = Instant.now();
    this.updatedAt = Instant.now();
  }

  public boolean isReleased() {
    return status == ReservationStatus.RELEASED;
  }

  public void release() {
    this.status = ReservationStatus.RELEASED;
    this.updatedAt = Instant.now();
  }

  public UUID getReservationId() {
    return reservationId;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public ReservationStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
