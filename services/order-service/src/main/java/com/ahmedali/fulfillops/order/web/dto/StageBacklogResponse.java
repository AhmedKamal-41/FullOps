package com.ahmedali.fulfillops.order.web.dto;

public record StageBacklogResponse(
    String stage, long openOrderCount, long slaBreachedCount, double slaBreachRate) {}
