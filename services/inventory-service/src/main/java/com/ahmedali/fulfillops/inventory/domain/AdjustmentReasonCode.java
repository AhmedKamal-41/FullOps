package com.ahmedali.fulfillops.inventory.domain;

/** The closed set of reasons an ADMIN may give for a manual stock adjustment. */
public enum AdjustmentReasonCode {
  RESTOCK,
  DAMAGE,
  CORRECTION,
  RETURN,
  MANUAL_COUNT
}
