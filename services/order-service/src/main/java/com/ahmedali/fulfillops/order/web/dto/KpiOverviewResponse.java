package com.ahmedali.fulfillops.order.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * Every formula here is defined exactly in docs/KPI_DICTIONARY.md — rates are 0.0 when their
 * denominator is zero (an empty window), never NaN or a fabricated placeholder.
 */
public record KpiOverviewResponse(
    Instant from,
    Instant to,
    long ordersReceived,
    long ordersCompleted,
    long ordersCancelled,
    long inventoryRejections,
    double inventoryRejectionRate,
    List<ReasonCodeCountResponse> inventoryRejectionReasons,
    long paymentDeclines,
    double paymentDeclineRate,
    List<ReasonCodeCountResponse> paymentDeclineReasons,
    double paymentTechnicalFailureRate,
    long fulfillmentThroughput,
    long dltBacklogCount,
    Instant oldestDltEventAt,
    long outboxBacklogCount,
    Instant oldestOutboxEventAt,
    double recoverySuccessRate,
    double manualTouchRate) {}
