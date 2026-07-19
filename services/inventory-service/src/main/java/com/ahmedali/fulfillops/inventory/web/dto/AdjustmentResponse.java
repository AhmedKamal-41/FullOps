package com.ahmedali.fulfillops.inventory.web.dto;

import java.time.Instant;
import java.util.UUID;

public record AdjustmentResponse(
    UUID adjustmentId,
    String sku,
    int changeQuantity,
    int quantityBefore,
    int quantityAfter,
    String reasonCode,
    String reasonDetail,
    String actor,
    Instant createdAt) {}
