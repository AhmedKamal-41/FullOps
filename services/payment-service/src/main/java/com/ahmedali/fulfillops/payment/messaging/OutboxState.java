package com.ahmedali.fulfillops.payment.messaging;

/** Matches the outbox_event.state CHECK constraint. */
public enum OutboxState {
  PENDING,
  PUBLISHED,
  FAILED
}
