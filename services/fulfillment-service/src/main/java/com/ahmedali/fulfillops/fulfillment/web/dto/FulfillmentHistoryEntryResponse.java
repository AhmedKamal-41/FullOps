package com.ahmedali.fulfillops.fulfillment.web.dto;

import java.time.Instant;

public record FulfillmentHistoryEntryResponse(
    String status, String actor, String notes, Instant occurredAt) {}
