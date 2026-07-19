package com.ahmedali.fulfillops.inventory.cache;

import java.time.Instant;

/**
 * A point-in-time read of one SKU's stock, either served from Redis or freshly read from Postgres.
 */
public record AvailabilitySnapshot(
    String sku, int availableQuantity, int reservedQuantity, Instant updatedAt) {}
