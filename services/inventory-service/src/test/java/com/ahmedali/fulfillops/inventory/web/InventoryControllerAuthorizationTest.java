package com.ahmedali.fulfillops.inventory.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ahmedali.fulfillops.inventory.cache.AvailabilitySnapshot;
import com.ahmedali.fulfillops.inventory.config.SecurityConfig;
import com.ahmedali.fulfillops.inventory.config.TestSecurityConfig;
import com.ahmedali.fulfillops.inventory.service.InventoryQueryService;
import com.ahmedali.fulfillops.inventory.service.ProductService;
import com.ahmedali.fulfillops.inventory.service.StockAdjustmentService;
import com.ahmedali.fulfillops.inventory.web.dto.AdjustmentResponse;
import com.ahmedali.fulfillops.inventory.web.dto.ProductResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Every request carries a JWT built with jwt() — see order-service's
 * OrderControllerAuthorizationTest for why this doesn't need a running Keycloak. What's under test
 * is SecurityConfig's URL-based role rules (ADMIN-only commands, OPERATOR/ADMIN reads), not token
 * validation itself.
 */
@WebMvcTest(controllers = {ProductController.class, InventoryController.class})
@Import({SecurityConfig.class, TestSecurityConfig.class, GlobalExceptionHandler.class})
class InventoryControllerAuthorizationTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ProductService productService;
  @MockitoBean private StockAdjustmentService stockAdjustmentService;
  @MockitoBean private InventoryQueryService inventoryQueryService;

  private static final String CREATE_PRODUCT_BODY =
      """
          {"sku": "WIDGET-BLUE-M", "name": "Blue Widget", "description": "A fictional blue widget"}
          """;

  private static final String ADJUST_STOCK_BODY =
      """
          {"changeQuantity": 10, "reasonCode": "RESTOCK", "reasonDetail": "quarterly restock"}
          """;

  @Test
  void adminCanCreateAProduct() throws Exception {
    when(productService.createProduct(any(), any(), any())).thenReturn(sampleProductResponse());

    mockMvc
        .perform(
            post("/api/v1/products")
                .with(jwtWithRole("ADMIN"))
                .header("Idempotency-Key", "create-1")
                .contentType("application/json")
                .content(CREATE_PRODUCT_BODY))
        .andExpect(status().isCreated());
  }

  @Test
  void operatorCannotCreateAProduct() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/products")
                .with(jwtWithRole("OPERATOR"))
                .header("Idempotency-Key", "create-1")
                .contentType("application/json")
                .content(CREATE_PRODUCT_BODY))
        .andExpect(status().isForbidden());
  }

  @Test
  void unauthenticatedCannotCreateAProduct() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/products")
                .header("Idempotency-Key", "create-1")
                .contentType("application/json")
                .content(CREATE_PRODUCT_BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void creatingAProductWithoutAnIdempotencyKeyIsRejected() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/products")
                .with(jwtWithRole("ADMIN"))
                .contentType("application/json")
                .content(CREATE_PRODUCT_BODY))
        .andExpect(status().isBadRequest());
  }

  @Test
  void adminCanAdjustStock() throws Exception {
    when(stockAdjustmentService.adjustStock(any(), any(), any(), any(), any()))
        .thenReturn(sampleAdjustmentResponse());

    mockMvc
        .perform(
            post("/api/v1/inventory/WIDGET-BLUE-M/adjustments")
                .with(jwtWithRole("ADMIN"))
                .header("Idempotency-Key", "adjust-1")
                .contentType("application/json")
                .content(ADJUST_STOCK_BODY))
        .andExpect(status().isCreated());
  }

  @Test
  void operatorCannotAdjustStock() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/inventory/WIDGET-BLUE-M/adjustments")
                .with(jwtWithRole("OPERATOR"))
                .header("Idempotency-Key", "adjust-1")
                .contentType("application/json")
                .content(ADJUST_STOCK_BODY))
        .andExpect(status().isForbidden());
  }

  @Test
  void operatorCanReadAvailability() throws Exception {
    when(inventoryQueryService.getAvailability("WIDGET-BLUE-M"))
        .thenReturn(Optional.of(new AvailabilitySnapshot("WIDGET-BLUE-M", 8, 2, Instant.now())));

    mockMvc
        .perform(get("/api/v1/inventory/WIDGET-BLUE-M").with(jwtWithRole("OPERATOR")))
        .andExpect(status().isOk());
  }

  @Test
  void adminCanReadAvailability() throws Exception {
    when(inventoryQueryService.getAvailability("WIDGET-BLUE-M"))
        .thenReturn(Optional.of(new AvailabilitySnapshot("WIDGET-BLUE-M", 8, 2, Instant.now())));

    mockMvc
        .perform(get("/api/v1/inventory/WIDGET-BLUE-M").with(jwtWithRole("ADMIN")))
        .andExpect(status().isOk());
  }

  @Test
  void unauthenticatedCannotReadAvailability() throws Exception {
    mockMvc.perform(get("/api/v1/inventory/WIDGET-BLUE-M")).andExpect(status().isUnauthorized());
  }

  @Test
  void operatorCanReadLowStock() throws Exception {
    when(inventoryQueryService.getLowStock(any())).thenReturn(List.of());

    mockMvc
        .perform(get("/api/v1/inventory/low-stock").with(jwtWithRole("OPERATOR")))
        .andExpect(status().isOk());
  }

  private static JwtRequestPostProcessor jwtWithRole(String role) {
    return jwt()
        .jwt(token -> token.subject(UUID.randomUUID().toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_" + role));
  }

  private static ProductResponse sampleProductResponse() {
    return new ProductResponse(
        UUID.randomUUID(),
        "WIDGET-BLUE-M",
        "Blue Widget",
        "A fictional blue widget",
        Instant.now());
  }

  private static AdjustmentResponse sampleAdjustmentResponse() {
    return new AdjustmentResponse(
        UUID.randomUUID(),
        "WIDGET-BLUE-M",
        10,
        0,
        10,
        "RESTOCK",
        "quarterly restock",
        UUID.randomUUID().toString(),
        Instant.now());
  }
}
