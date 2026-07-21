package com.ahmedali.fulfillops.fulfillment.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelFulfillmentRequest(@NotBlank @Size(max = 500) String reasonDetail) {}
