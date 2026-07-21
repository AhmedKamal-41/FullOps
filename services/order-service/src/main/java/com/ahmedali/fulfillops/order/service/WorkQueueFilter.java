package com.ahmedali.fulfillops.order.service;

import com.ahmedali.fulfillops.order.domain.OrderStatus;
import java.util.UUID;

/** Every field is optional — an absent filter simply isn't applied. */
public record WorkQueueFilter(
    OrderStatus status, UUID customerId, Boolean slaBreached, Boolean stuck) {}
