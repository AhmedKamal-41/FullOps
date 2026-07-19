package com.ahmedali.fulfillops.inventory.service;

import java.util.List;
import java.util.UUID;

/**
 * A reservation attempt's business result. Insufficient stock is an ordinary, expected outcome —
 * not an exception — because it is not a concurrency conflict and must never be retried.
 */
public sealed interface ReservationOutcome {

  record Reserved(UUID reservationId, List<RequestedItem> items) implements ReservationOutcome {}

  record Rejected(List<RejectedItem> rejectedItems) implements ReservationOutcome {}

  record RejectedItem(String sku, int requestedQuantity, int availableQuantity) {}
}
