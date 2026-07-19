package com.ahmedali.fulfillops.inventory.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * changeQuantity may be positive (restock) or negative (write-off) but never zero — a zero-quantity
 * adjustment has no effect and isn't a real audit event. reasonCode must name one of
 * AdjustmentReasonCode's values; StockAdjustmentService is what validates that and turns an unknown
 * value into a 400, since Bean Validation alone can't check membership in a Java enum by name here
 * without coupling this DTO to the domain enum type.
 */
public record AdjustStockRequest(
    @NotNull int changeQuantity,
    @NotBlank @Size(max = 50) String reasonCode,
    @Size(max = 500) String reasonDetail) {}
