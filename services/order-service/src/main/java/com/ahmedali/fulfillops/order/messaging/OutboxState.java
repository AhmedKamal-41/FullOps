package com.ahmedali.fulfillops.order.messaging;

/** Matches the outbox_event.state CHECK constraint from the baseline messaging migration. */
public enum OutboxState {
  PENDING,
  PUBLISHED,
  FAILED
}
