package com.ahmedali.fulfillops.order.web.dto;

import java.time.Instant;

/**
 * type is "STATUS_CHANGE" (status/reasonCode set, actor/detail null) or "INCIDENT_ACTION"
 * (actor/detail set, status/reasonCode null) — one merged, chronologically sorted timeline for an
 * order instead of two separate lists an operator would have to interleave by hand.
 */
public record TimelineEntryResponse(
    String type,
    Instant occurredAt,
    String status,
    String reasonCode,
    String actor,
    String detail) {}
