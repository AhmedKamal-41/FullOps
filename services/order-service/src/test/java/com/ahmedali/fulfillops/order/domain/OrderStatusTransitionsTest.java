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
  void cancellationIsOnlyAllowedBeforeDispatch() {
    assertThat(OrderStatusTransitions.isAllowed(OrderStatus.PACKED, OrderStatus.CANCELLED))
        .isTrue();
    assertThat(OrderStatusTransitions.isAllowed(OrderStatus.DISPATCHED, OrderStatus.CANCELLED))
        .isFalse();
  }

  @Test
  void requiresReviewCanOnlyResolveToCancelled() {
    assertThat(OrderStatusTransitions.isAllowed(OrderStatus.REQUIRES_REVIEW, OrderStatus.CANCELLED))
        .isTrue();
    assertThat(OrderStatusTransitions.isAllowed(OrderStatus.REQUIRES_REVIEW, OrderStatus.DELIVERED))
        .isFalse();
  }
}
