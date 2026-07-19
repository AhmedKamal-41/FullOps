package com.ahmedali.fulfillops.order.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * amount is a decimal string with exactly 2 fractional digits, not a JSON number — the same
 * convention contracts/events/Money.v1.schema.json uses and for the same reason: a JSON number can
 * lose precision when a client parses it into a native float/double, a string round-trips exactly.
 */
public record MoneyDto(
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO 4217 code")
        String currencyCode,
    @NotBlank
        @Pattern(
            regexp = "^[0-9]+\\.[0-9]{2}$",
            message = "must be a positive decimal with 2 fractional digits")
        String amount) {}
