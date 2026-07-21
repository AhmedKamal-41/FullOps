package com.ahmedali.fulfillops.inventory.service;

import com.ahmedali.fulfillops.inventory.messaging.OutboxEventWriter;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Emits InventoryLowStock.v1 exactly when a SKU's available quantity crosses the configured
 * low-stock threshold, in either direction — never repeated while already on the same side of it.
 * Uses the same "low" definition (availableQuantity &lt;= threshold) as
 * InventoryQueryService.getLowStock, so this event and the existing GET .../low-stock endpoint
 * never disagree about what counts as low.
 *
 * <p>Called from every mutation that changes availableQuantity (reservation, release, admin
 * adjustment) right after it already computes before/after, so detecting a crossing needs no extra
 * read or stored state — just those two numbers.
 */
@Component
public class LowStockSignalEvaluator {

  private static final String EVENT_TYPE = "InventoryLowStock";
  private static final int EVENT_VERSION = 1;

  private final OutboxEventWriter outboxEventWriter;
  private final int threshold;

  public LowStockSignalEvaluator(
      OutboxEventWriter outboxEventWriter,
      @Value("${app.inventory.low-stock.default-threshold}") int threshold) {
    this.outboxEventWriter = outboxEventWriter;
    this.threshold = threshold;
  }

  public void evaluate(
      String sku, int quantityBefore, int quantityAfter, UUID correlationId, UUID causationId) {
    boolean wasBelowThreshold = quantityBefore <= threshold;
    boolean isBelowThreshold = quantityAfter <= threshold;
    if (wasBelowThreshold == isBelowThreshold) {
      return;
    }

    outboxEventWriter.write(
        EVENT_TYPE,
        EVENT_VERSION,
        aggregateIdForSku(sku),
        correlationId,
        causationId,
        new LowStockPayload(sku, quantityAfter, threshold, isBelowThreshold));
  }

  // InventoryLowStock.v1 has no order to key by (see contracts/events/README.md's aggregateId
  // exception for this event) — a name-based UUID keeps every event for one SKU on the same Kafka
  // partition without needing a lookup table to remember a SKU's assigned UUID.
  private static UUID aggregateIdForSku(String sku) {
    return UUID.nameUUIDFromBytes(("sku:" + sku).getBytes(StandardCharsets.UTF_8));
  }

  private record LowStockPayload(
      String sku, int availableQuantity, int threshold, boolean belowThreshold) {}
}
