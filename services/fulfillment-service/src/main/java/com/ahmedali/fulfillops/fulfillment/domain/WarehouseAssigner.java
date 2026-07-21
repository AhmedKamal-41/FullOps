package com.ahmedali.fulfillops.fulfillment.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Picks a fictional warehouse for a new fulfillment. Deterministic by orderId rather than random,
 * so the same order always lands on the same warehouse (useful for tests and for a human looking at
 * the same order twice) without needing a real warehouse-management system.
 */
@Component
public class WarehouseAssigner {

  private final List<String> warehouseIds;

  public WarehouseAssigner(@Value("${app.fulfillment.warehouse-ids}") List<String> warehouseIds) {
    if (warehouseIds.isEmpty()) {
      throw new IllegalStateException("app.fulfillment.warehouse-ids must not be empty");
    }
    this.warehouseIds = warehouseIds;
  }

  public String assign(UUID orderId) {
    int index = Math.floorMod(orderId.hashCode(), warehouseIds.size());
    return warehouseIds.get(index);
  }
}
