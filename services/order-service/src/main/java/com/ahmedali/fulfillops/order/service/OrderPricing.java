package com.ahmedali.fulfillops.order.service;

import com.ahmedali.fulfillops.order.web.dto.CreateOrderItemRequest;
import com.ahmedali.fulfillops.order.web.dto.CreateOrderRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The only place order totals are computed. The server never accepts a client-supplied total — a
 * client can't even send one, since CreateOrderRequest has no such field — this derives it from
 * validated line items instead.
 */
public final class OrderPricing {

  private OrderPricing() {}

  public record ComputedItem(
      String sku, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {}

  public record Computed(List<ComputedItem> items, String currencyCode, BigDecimal totalAmount) {}

  public static Computed compute(CreateOrderRequest request) {
    String currencyCode = request.items().getFirst().unitPrice().currencyCode();
    List<ComputedItem> items = new ArrayList<>();
    BigDecimal totalAmount = BigDecimal.ZERO;

    for (CreateOrderItemRequest item : request.items()) {
      if (!item.unitPrice().currencyCode().equals(currencyCode)) {
        throw new InvalidOrderRequestException("All items in an order must use the same currency");
      }
      BigDecimal unitPrice = new BigDecimal(item.unitPrice().amount());
      BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(item.quantity()));
      items.add(new ComputedItem(item.sku(), item.quantity(), unitPrice, lineTotal));
      totalAmount = totalAmount.add(lineTotal);
    }

    return new Computed(items, currencyCode, totalAmount);
  }
}
