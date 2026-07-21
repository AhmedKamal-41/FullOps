package com.ahmedali.fulfillops.order.web.dto;

import java.time.Instant;
import java.util.UUID;

public record WorkQueueItemResponse(
    UUID orderId,
    UUID customerId,
    String status,
    MoneyDto totalAmount,
    Instant createdAt,
    Instant currentStageEnteredAt,
    Instant updatedAt,
    String inventoryRejectionReasonCode,
    String paymentDeclineReasonCode,
    int paymentTechnicalFailureCount,
    String cancellationReasonCode,
    String requiresReviewReasonCode,
    int openIncidentCount) {}
