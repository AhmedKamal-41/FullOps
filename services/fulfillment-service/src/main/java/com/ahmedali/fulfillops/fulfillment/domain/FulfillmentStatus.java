package com.ahmedali.fulfillops.fulfillment.domain;

/** The states from docs/ARCHITECTURE.md's fulfillment state machine. */
public enum FulfillmentStatus {
  ASSIGNED,
  PICKING,
  PACKED,
  DISPATCHED,
  DELIVERED,
  CANCELLED
}
