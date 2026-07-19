package com.ahmedali.fulfillops.payment.web.dto;

import jakarta.validation.constraints.NotBlank;

public record RefundRequest(@NotBlank String reasonCode) {}
