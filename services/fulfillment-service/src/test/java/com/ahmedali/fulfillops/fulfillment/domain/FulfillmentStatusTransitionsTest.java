package com.ahmedali.fulfillops.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FulfillmentStatusTransitionsTest {

  @Test
  void theForwardHappyPathIsAllowed() {
    assertThat(
            FulfillmentStatusTransitions.isAllowedAdvance(
                FulfillmentStatus.ASSIGNED, FulfillmentStatus.PICKING))
        .isTrue();
    assertThat(
            FulfillmentStatusTransitions.isAllowedAdvance(
                FulfillmentStatus.PICKING, FulfillmentStatus.PACKED))
        .isTrue();
    assertThat(
            FulfillmentStatusTransitions.isAllowedAdvance(
                FulfillmentStatus.PACKED, FulfillmentStatus.DISPATCHED))
        .isTrue();
    assertThat(
            FulfillmentStatusTransitions.isAllowedAdvance(
                FulfillmentStatus.DISPATCHED, FulfillmentStatus.DELIVERED))
        .isTrue();
  }

  @Test
  void skippingAheadIsRejected() {
    assertThat(
            FulfillmentStatusTransitions.isAllowedAdvance(
                FulfillmentStatus.ASSIGNED, FulfillmentStatus.PACKED))
        .isFalse();
    assertThat(
            FulfillmentStatusTransitions.isAllowedAdvance(
                FulfillmentStatus.ASSIGNED, FulfillmentStatus.DISPATCHED))
        .isFalse();
    assertThat(
            FulfillmentStatusTransitions.isAllowedAdvance(
                FulfillmentStatus.PICKING, FulfillmentStatus.DISPATCHED))
        .isFalse();
  }

  @Test
  void movingBackwardIsRejected() {
    assertThat(
            FulfillmentStatusTransitions.isAllowedAdvance(
                FulfillmentStatus.PACKED, FulfillmentStatus.PICKING))
        .isFalse();
    assertThat(
            FulfillmentStatusTransitions.isAllowedAdvance(
                FulfillmentStatus.DELIVERED, FulfillmentStatus.DISPATCHED))
        .isFalse();
  }

  @Test
  void terminalStatusesAllowNothingFurther() {
    for (FulfillmentStatus target : FulfillmentStatus.values()) {
      assertThat(FulfillmentStatusTransitions.isAllowedAdvance(FulfillmentStatus.DELIVERED, target))
          .isFalse();
      assertThat(FulfillmentStatusTransitions.isAllowedAdvance(FulfillmentStatus.CANCELLED, target))
          .isFalse();
    }
  }

  @Test
  void onlyAssignedPickingAndPackedAreCancellable() {
    assertThat(FulfillmentStatusTransitions.isCancellable(FulfillmentStatus.ASSIGNED)).isTrue();
    assertThat(FulfillmentStatusTransitions.isCancellable(FulfillmentStatus.PICKING)).isTrue();
    assertThat(FulfillmentStatusTransitions.isCancellable(FulfillmentStatus.PACKED)).isTrue();
    assertThat(FulfillmentStatusTransitions.isCancellable(FulfillmentStatus.DISPATCHED)).isFalse();
    assertThat(FulfillmentStatusTransitions.isCancellable(FulfillmentStatus.DELIVERED)).isFalse();
    assertThat(FulfillmentStatusTransitions.isCancellable(FulfillmentStatus.CANCELLED)).isFalse();
  }
}
