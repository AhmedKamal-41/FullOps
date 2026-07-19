package com.ahmedali.fulfillops.payment.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
    UUID paymentId,
    UUID orderId,
    UUID customerId,
    BigDecimal amount,
    String currencyCode,
    String status,
    String declineReasonCode,
    String declineReasonDetail,
    Instant createdAt,
    Instant updatedAt) {}
