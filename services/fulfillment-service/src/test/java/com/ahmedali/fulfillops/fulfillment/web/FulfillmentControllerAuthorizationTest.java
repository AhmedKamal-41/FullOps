package com.ahmedali.fulfillops.fulfillment.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ahmedali.fulfillops.fulfillment.config.SecurityConfig;
import com.ahmedali.fulfillops.fulfillment.config.TestSecurityConfig;
import com.ahmedali.fulfillops.fulfillment.domain.Fulfillment;
import com.ahmedali.fulfillops.fulfillment.service.FulfillmentCommandService;
import com.ahmedali.fulfillops.fulfillment.service.FulfillmentQueryService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Every request carries a JWT built with jwt() — see inventory-service's
 * InventoryControllerAuthorizationTest for why this doesn't need a running Keycloak. What's under
 * test is SecurityConfig's OPERATOR/ADMIN-only rule for /api/v1/fulfillments/**, not token
 * validation itself, and that a missing If-Match header is rejected before it ever reaches the
 * service layer.
 */
@WebMvcTest(FulfillmentController.class)
@Import({SecurityConfig.class, TestSecurityConfig.class, GlobalExceptionHandler.class})
class FulfillmentControllerAuthorizationTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private FulfillmentQueryService fulfillmentQueryService;
  @MockitoBean private FulfillmentCommandService fulfillmentCommandService;

  @Test
  void operatorCanListFulfillments() throws Exception {
    Page<Fulfillment> page = new PageImpl<>(List.of(sampleFulfillment()));
    when(fulfillmentQueryService.list(any(), any())).thenReturn(page);

    mockMvc
        .perform(get("/api/v1/fulfillments").with(jwtWithRole("OPERATOR")))
        .andExpect(status().isOk());
  }

  @Test
  void adminCanListFulfillments() throws Exception {
    Page<Fulfillment> page = new PageImpl<>(List.of(sampleFulfillment()));
    when(fulfillmentQueryService.list(any(), any())).thenReturn(page);

    mockMvc
        .perform(get("/api/v1/fulfillments").with(jwtWithRole("ADMIN")))
        .andExpect(status().isOk());
  }

  @Test
  void customerCannotListFulfillments() throws Exception {
    mockMvc
        .perform(get("/api/v1/fulfillments").with(jwtWithRole("CUSTOMER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void unauthenticatedCannotListFulfillments() throws Exception {
    mockMvc.perform(get("/api/v1/fulfillments")).andExpect(status().isUnauthorized());
  }

  @Test
  void operatorCanClaimWithAnIfMatchHeader() throws Exception {
    UUID fulfillmentId = UUID.randomUUID();
    when(fulfillmentCommandService.claim(any(), anyLong(), any())).thenReturn(sampleFulfillment());

    mockMvc
        .perform(
            post("/api/v1/fulfillments/{id}/claim", fulfillmentId)
                .with(jwtWithRole("OPERATOR"))
                .header("If-Match", "0"))
        .andExpect(status().isOk());
  }

  @Test
  void claimingWithoutAnIfMatchHeaderIsRejected() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/fulfillments/{id}/claim", UUID.randomUUID())
                .with(jwtWithRole("OPERATOR")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void customerCannotClaim() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/fulfillments/{id}/claim", UUID.randomUUID())
                .with(jwtWithRole("CUSTOMER"))
                .header("If-Match", "0"))
        .andExpect(status().isForbidden());
  }

  @Test
  void operatorCanAdvanceStatus() throws Exception {
    when(fulfillmentCommandService.advance(
            any(), anyLong(), any(), any(), any(), any(), any(), any()))
        .thenReturn(sampleFulfillment());

    mockMvc
        .perform(
            patch("/api/v1/fulfillments/{id}/status", UUID.randomUUID())
                .with(jwtWithRole("OPERATOR"))
                .header("If-Match", "0")
                .contentType("application/json")
                .content(
                    """
                    {"newStatus": "PICKING"}
                    """))
        .andExpect(status().isOk());
  }

  @Test
  void operatorCanCancel() throws Exception {
    when(fulfillmentCommandService.cancel(any(), anyLong(), any(), any(), any()))
        .thenReturn(sampleFulfillment());

    mockMvc
        .perform(
            post("/api/v1/fulfillments/{id}/cancel", UUID.randomUUID())
                .with(jwtWithRole("OPERATOR"))
                .header("If-Match", "0")
                .contentType("application/json")
                .content(
                    """
                    {"reasonDetail": "customer requested cancellation"}
                    """))
        .andExpect(status().isOk());
  }

  @Test
  void customerCannotCancel() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/fulfillments/{id}/cancel", UUID.randomUUID())
                .with(jwtWithRole("CUSTOMER"))
                .header("If-Match", "0")
                .contentType("application/json")
                .content(
                    """
                    {"reasonDetail": "customer requested cancellation"}
                    """))
        .andExpect(status().isForbidden());
  }

  private static JwtRequestPostProcessor jwtWithRole(String role) {
    return jwt()
        .jwt(token -> token.subject(UUID.randomUUID().toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_" + role));
  }

  private static Fulfillment sampleFulfillment() {
    return Fulfillment.create(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "WH-ATL-1",
        Instant.now().plusSeconds(3600),
        UUID.randomUUID());
  }
}
