package com.ahmedali.fulfillops.fulfillment.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * newStatus must be one of PICKING, PACKED, DISPATCHED, DELIVERED — see
 * FulfillmentCommandService.parseAdvanceableStatus. trackingReference is required only when moving
 * to DISPATCHED; deliveredAt only when moving to DELIVERED.
 */
public record AdvanceFulfillmentRequest(
    @NotBlank String newStatus,
    @Size(max = 128) String trackingReference,
    Instant deliveredAt,
    @Size(max = 500) String notes) {}
