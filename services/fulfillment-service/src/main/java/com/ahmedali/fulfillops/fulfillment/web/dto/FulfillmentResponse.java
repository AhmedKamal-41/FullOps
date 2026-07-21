package com.ahmedali.fulfillops.fulfillment.web.dto;

import java.time.Instant;
import java.util.UUID;

public record FulfillmentResponse(
    UUID fulfillmentId,
    UUID orderId,
    String status,
    String warehouseId,
    String assigneeId,
    Instant slaDueAt,
    String trackingReference,
    Instant deliveredAt,
    String cancellationReasonCode,
    String cancellationReasonDetail,
    long version,
    Instant createdAt,
    Instant updatedAt) {}
