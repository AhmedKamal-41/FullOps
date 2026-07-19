package com.ahmedali.fulfillops.order.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * The list view (GET /api/v1/orders) omits line items to keep pages small; fetch GET
 * /api/v1/orders/{orderId} for the full order.
 */
public record OrderSummaryResponse(
    UUID orderId, String status, MoneyDto totalAmount, Instant createdAt) {}
