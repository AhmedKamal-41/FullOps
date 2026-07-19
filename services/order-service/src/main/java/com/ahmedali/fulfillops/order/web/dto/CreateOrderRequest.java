package com.ahmedali.fulfillops.order.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * No customerId field: the order belongs to the authenticated caller (the JWT subject), never a
 * client-supplied value. No totalAmount field either — the server always computes it from items,
 * never accepts one.
 */
public record CreateOrderRequest(
    @NotEmpty @Size(max = 50) List<@Valid CreateOrderItemRequest> items) {}
