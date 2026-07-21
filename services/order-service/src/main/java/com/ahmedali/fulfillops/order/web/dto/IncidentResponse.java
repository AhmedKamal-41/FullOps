package com.ahmedali.fulfillops.order.web.dto;

import java.time.Instant;
import java.util.UUID;

public record IncidentResponse(
    UUID incidentId,
    UUID orderId,
    String kind,
    String detail,
    String status,
    Instant createdAt,
    Instant acknowledgedAt,
    String acknowledgedBy,
    String assignedTo,
    Instant assignedAt,
    Instant resolvedAt,
    String resolvedBy,
    String resolutionNote) {}
