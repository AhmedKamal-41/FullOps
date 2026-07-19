package com.ahmedali.fulfillops.inventory.web.dto;

import java.time.Instant;
import java.util.UUID;

public record ProductResponse(
    UUID productId, String sku, String name, String description, Instant createdAt) {}
