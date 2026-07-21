package com.ahmedali.fulfillops.order.web.dto;

import jakarta.validation.constraints.Size;

public record ResolveIncidentRequest(@Size(max = 500) String resolutionNote) {}
