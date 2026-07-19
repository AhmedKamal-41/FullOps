package com.ahmedali.fulfillops.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** One line item of a reservation: how much of one SKU it holds. */
@Entity
@Table(name = "reservation_item")
public class ReservationItem {

  @Id private UUID reservationItemId;

  @Column(name = "reservation_id")
  private UUID reservationId;

  private String sku;
  private int quantity;

  protected ReservationItem() {
    // JPA
  }

  public ReservationItem(UUID reservationItemId, UUID reservationId, String sku, int quantity) {
    this.reservationItemId = reservationItemId;
    this.reservationId = reservationId;
    this.sku = sku;
    this.quantity = quantity;
  }

  public String getSku() {
    return sku;
  }

  public int getQuantity() {
    return quantity;
  }
}
