package com.ahmedali.fulfillops.order.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * unitPrice comes from the client because this project has no product catalog to look it up from.
 * What the server never trusts is a client-supplied *total*: OrderService always computes totals
 * from these fields.
 */
public record CreateOrderItemRequest(
    @NotBlank @Size(max = 64) String sku,
    @Min(1) @Max(10_000) int quantity,
    @NotNull @Valid MoneyDto unitPrice) {}
