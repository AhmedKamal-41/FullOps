package com.ahmedali.fulfillops.order.web.dto;

import java.time.Instant;
import java.util.List;

public record KpiTimeSeriesResponse(
    Instant from, Instant to, String interval, List<TimeSeriesPointResponse> points) {}
