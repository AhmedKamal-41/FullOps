package com.ahmedali.fulfillops.order.web.dto;

import jakarta.validation.constraints.NotBlank;

public record AssignIncidentRequest(@NotBlank String assignee) {}
