package com.ahmedali.fulfillops.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WarehouseAssignerTest {

  private final List<String> warehouseIds = List.of("WH-ATL-1", "WH-DFW-2", "WH-SEA-3");
  private final WarehouseAssigner assigner = new WarehouseAssigner(warehouseIds);

  @Test
  void alwaysReturnsOneOfTheConfiguredWarehouses() {
    UUID orderId = UUID.randomUUID();
    assertThat(warehouseIds).contains(assigner.assign(orderId));
  }

  @Test
  void isDeterministicForTheSameOrder() {
    UUID orderId = UUID.randomUUID();
    assertThat(assigner.assign(orderId)).isEqualTo(assigner.assign(orderId));
  }
}
