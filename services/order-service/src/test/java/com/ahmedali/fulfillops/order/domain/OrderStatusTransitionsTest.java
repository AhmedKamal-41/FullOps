package com.ahmedali.fulfillops.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrderStatusTransitionsTest {

  @Test
  void allowsTheHappyPathForward() {
    assertThat(
            OrderStatusTransitions.isAllowed(OrderStatus.PENDING, OrderStatus.INVENTORY_RESERVED))
        .isTrue();
    assertThat(
            OrderStatusTransitions.isAllowed(
                OrderStatus.INVENTORY_RESERVED, OrderStatus.PAYMENT_AUTHORIZED))
        .isTrue();
    assertThat(
            OrderStatusTransitions.isAllowed(
                OrderStatus.PAYMENT_AUTHORIZED, OrderStatus.FULFILLMENT_ASSIGNED))
        .isTrue();
    assertThat(OrderStatusTransitions.isAllowed(OrderStatus.DISPATCHED, OrderStatus.DELIVERED))
        .isTrue();
  }

  @Test
  void rejectsSkippingAheadInTheWorkflow() {
    assertThat(OrderStatusTransitions.isAllowed(OrderStatus.PENDING, OrderStatus.DELIVERED))
        .isFalse();
    assertThat(
            OrderStatusTransitions.isAllowed(OrderStatus.PENDING, OrderStatus.PAYMENT_AUTHORIZED))
        .isFalse();
  }

  @Test
  void rejectsMovingBackward() {
    assertThat(
            OrderStatusTransitions.isAllowed(OrderStatus.PAYMENT_AUTHORIZED, OrderStatus.PENDING))
        .isFalse();
  }

  @Test
  void terminalStatusesAllowNoFurtherTransitions() {
    for (OrderStatus target : OrderStatus.values()) {
      assertThat(OrderStatusTransitions.isAllowed(OrderStatus.DELIVERED, target)).isFalse();
      assertThat(OrderStatusTransitions.isAllowed(OrderStatus.CANCELLED, target)).isFalse();
    }
  }

  @Test
  void cancellationBeforeDispatchGoesThroughCancellationPending() {
    assertThat(
            OrderStatusTransitions.isAllowed(OrderStatus.PACKED, OrderStatus.CANCELLATION_PENDING))
        .isTrue();
    assertThat(
            OrderStatusTransitions.isAllowed(
                OrderStatus.CANCELLATION_PENDING, OrderStatus.CANCELLED))
        .isTrue();
    assertThat(OrderStatusTransitions.isAllowed(OrderStatus.PACKED, OrderStatus.CANCELLED))
        .isFalse();
  }

  @Test
  void cancellationIsNeverDirectlyReachableAtOrAfterDispatch() {
    assertThat(OrderStatusTransitions.isAllowed(OrderStatus.DISPATCHED, OrderStatus.CANCELLED))
        .isFalse();
    assertThat(
            OrderStatusTransitions.isAllowed(
                OrderStatus.DISPATCHED, OrderStatus.CANCELLATION_PENDING))
        .isFalse();
  }

  @Test
  void cancellationRequestedAtOrAfterDispatchEscalatesStraightToRequiresReview() {
    assertThat(
            OrderStatusTransitions.isAllowed(OrderStatus.DISPATCHED, OrderStatus.REQUIRES_REVIEW))
        .isTrue();
  }

  @Test
  void onlyPendingCanCancelDirectlyWithNoCompensationToWaitFor() {
    assertThat(OrderStatusTransitions.isAllowed(OrderStatus.PENDING, OrderStatus.CANCELLED))
        .isTrue();
    assertThat(
            OrderStatusTransitions.isAllowed(OrderStatus.INVENTORY_RESERVED, OrderStatus.CANCELLED))
        .isFalse();
  }

  @Test
  void reconciliationCanEscalateAnyStuckHappyPathStatusToRequiresReview() {
    // Every status ReconciliationService.HAPPY_PATH_NONTERMINAL_STATUSES can find an order stuck
    // in must actually be able to reach REQUIRES_REVIEW, or the escalation silently no-ops.
    assertThat(OrderStatusTransitions.isAllowed(OrderStatus.PENDING, OrderStatus.REQUIRES_REVIEW))
        .isTrue();
    assertThat(
            OrderStatusTransitions.isAllowed(
                OrderStatus.INVENTORY_RESERVED, OrderStatus.REQUIRES_REVIEW))
        .isTrue();
    assertThat(
            OrderStatusTransitions.isAllowed(
                OrderStatus.PAYMENT_AUTHORIZED, OrderStatus.REQUIRES_REVIEW))
        .isTrue();
    assertThat(
            OrderStatusTransitions.isAllowed(
                OrderStatus.FULFILLMENT_ASSIGNED, OrderStatus.REQUIRES_REVIEW))
        .isTrue();
    assertThat(OrderStatusTransitions.isAllowed(OrderStatus.PICKING, OrderStatus.REQUIRES_REVIEW))
        .isTrue();
    assertThat(OrderStatusTransitions.isAllowed(OrderStatus.PACKED, OrderStatus.REQUIRES_REVIEW))
        .isTrue();
    assertThat(
            OrderStatusTransitions.isAllowed(OrderStatus.DISPATCHED, OrderStatus.REQUIRES_REVIEW))
        .isTrue();
  }

  @Test
  void requiresReviewCanOnlyResolveToCancelled() {
    assertThat(OrderStatusTransitions.isAllowed(OrderStatus.REQUIRES_REVIEW, OrderStatus.CANCELLED))
        .isTrue();
    assertThat(OrderStatusTransitions.isAllowed(OrderStatus.REQUIRES_REVIEW, OrderStatus.DELIVERED))
        .isFalse();
  }
}
