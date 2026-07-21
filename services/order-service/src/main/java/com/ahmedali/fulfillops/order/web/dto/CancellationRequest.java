package com.ahmedali.fulfillops.order.web.dto;

import jakarta.validation.constraints.Size;

/** reasonDetail is optional for a customer, but OrderCancellationService requires it for staff. */
public record CancellationRequest(@Size(max = 500) String reasonDetail) {}
