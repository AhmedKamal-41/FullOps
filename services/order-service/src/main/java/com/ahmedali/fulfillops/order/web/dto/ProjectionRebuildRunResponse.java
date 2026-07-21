package com.ahmedali.fulfillops.order.web.dto;

import java.time.Instant;
import java.util.UUID;

public record ProjectionRebuildRunResponse(
    UUID rebuildId,
    Instant startedAt,
    Instant completedAt,
    String status,
    String triggeredBy,
    Integer ordersProcessed,
    String failureDetail) {}
