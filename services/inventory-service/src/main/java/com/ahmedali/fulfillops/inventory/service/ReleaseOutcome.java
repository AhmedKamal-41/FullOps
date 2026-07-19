package com.ahmedali.fulfillops.inventory.service;

import java.util.List;
import java.util.UUID;

/** A release attempt's business result. Releasing an already-released reservation is a no-op. */
public sealed interface ReleaseOutcome {

  record Released(UUID reservationId, List<RequestedItem> items) implements ReleaseOutcome {}

  record AlreadyReleased(UUID reservationId) implements ReleaseOutcome {}
}
