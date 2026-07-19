package com.ahmedali.fulfillops.inventory.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProductRequest(
    @NotBlank @Size(max = 200) String name, @Size(max = 1000) String description) {}
