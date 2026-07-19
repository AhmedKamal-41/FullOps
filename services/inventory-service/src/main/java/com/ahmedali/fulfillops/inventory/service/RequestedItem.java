package com.ahmedali.fulfillops.inventory.service;

/** One SKU/quantity pair from an OrderPlaced.v1 payload that Inventory needs to reserve. */
public record RequestedItem(String sku, int quantity) {}
