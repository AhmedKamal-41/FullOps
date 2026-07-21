package com.ahmedali.fulfillops.order.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
    UUID orderId,
    UUID customerId,
    String status,
    List<OrderItemResponse> items,
    MoneyDto totalAmount,
    Instant createdAt,
    UUID correlationId) {}
