package com.ahmedali.fulfillops.order.web.dto;

public record OrderItemResponse(String sku, int quantity, MoneyDto unitPrice, MoneyDto lineTotal) {}
