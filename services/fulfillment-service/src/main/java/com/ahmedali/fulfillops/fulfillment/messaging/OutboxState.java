package com.ahmedali.fulfillops.fulfillment.messaging;

/** Matches the outbox_event.state CHECK constraint from the Phase 2 baseline migration. */
public enum OutboxState {
  PENDING,
  PUBLISHED,
  FAILED
}
