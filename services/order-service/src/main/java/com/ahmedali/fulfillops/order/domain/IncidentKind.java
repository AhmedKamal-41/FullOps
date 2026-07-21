package com.ahmedali.fulfillops.order.domain;

/**
 * COMPENSATION_EXHAUSTED and CANCELLATION_AFTER_DISPATCH mirror OrderRequiresReview.v1's reasonCode
 * enum in contracts/events/ — an incident of either kind is always paired with that event.
 * CANCELLATION_STUCK is reconciliation-only: an order left in CANCELLATION_PENDING past the
 * configured threshold. It escalates to a COMPENSATION_EXHAUSTED incident (and an actual
 * OrderRequiresReview.v1) only after one safe recovery attempt has already been made and the order
 * is still stuck — see ReconciliationService.
 */
public enum IncidentKind {
  COMPENSATION_EXHAUSTED,
  CANCELLATION_AFTER_DISPATCH,
  CANCELLATION_STUCK
}
