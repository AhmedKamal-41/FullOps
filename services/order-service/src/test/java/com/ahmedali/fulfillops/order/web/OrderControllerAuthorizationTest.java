package com.ahmedali.fulfillops.order.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ahmedali.fulfillops.order.config.SecurityConfig;
import com.ahmedali.fulfillops.order.config.TestSecurityConfig;
import com.ahmedali.fulfillops.order.service.OrderService;
import com.ahmedali.fulfillops.order.web.dto.MoneyDto;
import com.ahmedali.fulfillops.order.web.dto.OrderResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Every request in these tests carries a JWT built with jwt() (see WhoAmIAuthorizationTest in Phase
 * 2 for why this doesn't need a running Keycloak) — what's under test here is SecurityConfig's
 * URL-based role rules and OrderController's own ownership check, not token validation itself. The
 * subject is always set explicitly to a real UUID string: jwt()'s own default subject isn't one,
 * and the controller does UUID.fromString(sub).
 */
@WebMvcTest(OrderController.class)
@Import({SecurityConfig.class, TestSecurityConfig.class, GlobalExceptionHandler.class})
class OrderControllerAuthorizationTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private OrderService orderService;

  private static final String VALID_ORDER_BODY =
      """
            {"items": [{"sku": "WIDGET-BLUE-M", "quantity": 2, "unitPrice": {"currencyCode": "USD", "amount": "19.99"}}]}
            """;

  @Test
  void customerCanPlaceAnOrder() throws Exception {
    when(orderService.createOrder(any(), any(), any(), any())).thenReturn(sampleOrderResponse());

    mockMvc
        .perform(
            post("/api/v1/orders")
                .with(jwtWithRole("CUSTOMER"))
                .header("Idempotency-Key", "checkout-1")
                .contentType("application/json")
                .content(VALID_ORDER_BODY))
        .andExpect(status().isCreated());
  }

  @Test
  void operatorCannotPlaceAnOrder() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/orders")
                .with(jwtWithRole("OPERATOR"))
                .header("Idempotency-Key", "checkout-1")
                .contentType("application/json")
                .content(VALID_ORDER_BODY))
        .andExpect(status().isForbidden());
  }

  @Test
  void unauthenticatedCannotPlaceAnOrder() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/orders")
                .header("Idempotency-Key", "checkout-1")
                .contentType("application/json")
                .content(VALID_ORDER_BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void missingIdempotencyKeyIsRejected() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/orders")
                .with(jwtWithRole("CUSTOMER"))
                .contentType("application/json")
                .content(VALID_ORDER_BODY))
        .andExpect(status().isBadRequest());
  }

  @Test
  void emptyItemsListIsRejected() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/orders")
                .with(jwtWithRole("CUSTOMER"))
                .header("Idempotency-Key", "checkout-1")
                .contentType("application/json")
                .content("{\"items\": []}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void ownerCanReadTheirOwnOrder() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(orderService.getOrder(eq(orderId), any(), anyBoolean()))
        .thenReturn(Optional.of(sampleOrderResponse()));

    mockMvc
        .perform(get("/api/v1/orders/" + orderId).with(jwtWithRole("CUSTOMER")))
        .andExpect(status().isOk());
  }

  @Test
  void aNonOwnerNonStaffCustomerGetsNotFoundInsteadOfForbidden() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(orderService.getOrder(any(), any(), anyBoolean())).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/orders/" + orderId).with(jwtWithRole("CUSTOMER")))
        .andExpect(status().isNotFound());
  }

  @Test
  void operatorReadingAnOrderIsTreatedAsStaff() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(orderService.getOrder(any(), any(), eq(true)))
        .thenReturn(Optional.of(sampleOrderResponse()));

    mockMvc
        .perform(get("/api/v1/orders/" + orderId).with(jwtWithRole("OPERATOR")))
        .andExpect(status().isOk());
    verify(orderService).getOrder(any(), any(), eq(true));
  }

  @Test
  void customerCanListTheirOwnOrders() throws Exception {
    when(orderService.listOrders(any(), any())).thenReturn(Page.empty());

    mockMvc.perform(get("/api/v1/orders").with(jwtWithRole("CUSTOMER"))).andExpect(status().isOk());
  }

  @Test
  void operatorCannotListACustomersOrders() throws Exception {
    mockMvc
        .perform(get("/api/v1/orders").with(jwtWithRole("OPERATOR")))
        .andExpect(status().isForbidden());
  }

  private static JwtRequestPostProcessor jwtWithRole(String role) {
    return jwt()
        .jwt(token -> token.subject(UUID.randomUUID().toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_" + role));
  }

  private static OrderResponse sampleOrderResponse() {
    return new OrderResponse(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "PENDING",
        List.of(),
        new MoneyDto("USD", "39.98"),
        Instant.now());
  }
}
