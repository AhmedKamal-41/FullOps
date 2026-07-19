package com.ahmedali.fulfillops.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ahmedali.fulfillops.order.web.dto.CreateOrderItemRequest;
import com.ahmedali.fulfillops.order.web.dto.CreateOrderRequest;
import com.ahmedali.fulfillops.order.web.dto.MoneyDto;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderPricingTest {

  @Test
  void computesLineTotalsAndAnOverallTotalFromServerSideMath() {
    CreateOrderRequest request =
        new CreateOrderRequest(
            List.of(
                new CreateOrderItemRequest("WIDGET-BLUE-M", 2, new MoneyDto("USD", "19.99")),
                new CreateOrderItemRequest("WIDGET-RED-L", 1, new MoneyDto("USD", "24.50"))));

    OrderPricing.Computed computed = OrderPricing.compute(request);

    assertThat(computed.currencyCode()).isEqualTo("USD");
    assertThat(computed.items()).hasSize(2);
    assertThat(computed.items().get(0).lineTotal()).isEqualByComparingTo("39.98");
    assertThat(computed.totalAmount()).isEqualByComparingTo(new BigDecimal("64.48"));
  }

  @Test
  void rejectsMixedCurrenciesAcrossLineItems() {
    CreateOrderRequest request =
        new CreateOrderRequest(
            List.of(
                new CreateOrderItemRequest("WIDGET-BLUE-M", 1, new MoneyDto("USD", "19.99")),
                new CreateOrderItemRequest("WIDGET-RED-L", 1, new MoneyDto("EUR", "24.50"))));

    assertThatThrownBy(() -> OrderPricing.compute(request))
        .isInstanceOf(InvalidOrderRequestException.class);
  }
}
