package com.ahmedali.fulfillops.inventory.domain;

/** Matches the inventory_adjustment.source CHECK constraint. */
public enum AdjustmentSource {
  ADMIN_ADJUSTMENT,
  RESERVATION,
  RELEASE
}
