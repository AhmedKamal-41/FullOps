package com.ahmedali.fulfillops.order.web.dto;

public record CycleTimePercentilesResponse(
    Double p50Seconds, Double p90Seconds, Double p99Seconds, long sampleCount) {}
