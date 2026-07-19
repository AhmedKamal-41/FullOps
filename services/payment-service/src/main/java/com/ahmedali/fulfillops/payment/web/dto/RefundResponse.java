package com.ahmedali.fulfillops.payment.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RefundResponse(
    UUID refundId,
    UUID paymentId,
    BigDecimal amount,
    String currencyCode,
    String reasonCode,
    Instant createdAt) {}
