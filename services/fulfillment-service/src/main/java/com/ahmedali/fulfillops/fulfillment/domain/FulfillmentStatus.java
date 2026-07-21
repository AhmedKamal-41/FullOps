package com.ahmedali.fulfillops.fulfillment.domain;

/** The states from docs/DOMAIN_MODEL.md's fulfillment state machine. */
public enum FulfillmentStatus {
  ASSIGNED,
  PICKING,
  PACKED,
  DISPATCHED,
  DELIVERED,
  CANCELLED
}
