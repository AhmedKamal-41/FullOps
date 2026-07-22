package com.ahmedali.fulfillops.inventory.messaging;

/** Matches the outbox_event.state CHECK constraint from the baseline messaging migration. */
public enum OutboxState {
  PENDING,
  PUBLISHED,
  FAILED
}
