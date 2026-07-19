package com.ahmedali.fulfillops.payment.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ahmedali.fulfillops.payment.config.SecurityConfig;
import com.ahmedali.fulfillops.payment.config.TestSecurityConfig;
import com.ahmedali.fulfillops.payment.domain.Payment;
import com.ahmedali.fulfillops.payment.domain.Refund;
import com.ahmedali.fulfillops.payment.service.PaymentQueryService;
import com.ahmedali.fulfillops.payment.service.RefundService;
import java.math.BigDecimal;
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
 * Every request carries a JWT built with jwt() — see inventory-service's
 * InventoryControllerAuthorizationTest for why this doesn't need a running Keycloak. What's under
 * test is SecurityConfig's OPERATOR/ADMIN-only rule for /api/v1/payments/**, not token validation
 * itself.
 */
@WebMvcTest(PaymentController.class)
@Import({SecurityConfig.class, TestSecurityConfig.class, GlobalExceptionHandler.class})
class PaymentControllerAuthorizationTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private PaymentQueryService paymentQueryService;
  @MockitoBean private RefundService refundService;

  private static final String REFUND_BODY =
      """
      {"reasonCode": "FULFILLMENT_CANCELLED"}
      """;

  @Test
  void operatorCanReadPaymentStatus() throws Exception {
    UUID paymentId = UUID.randomUUID();
    when(paymentQueryService.findPayment(paymentId))
        .thenReturn(Optional.of(samplePayment(paymentId)));

    mockMvc
        .perform(get("/api/v1/payments/{paymentId}", paymentId).with(jwtWithRole("OPERATOR")))
        .andExpect(status().isOk());
  }

  @Test
  void adminCanReadPaymentStatus() throws Exception {
    UUID paymentId = UUID.randomUUID();
    when(paymentQueryService.findPayment(paymentId))
        .thenReturn(Optional.of(samplePayment(paymentId)));

    mockMvc
        .perform(get("/api/v1/payments/{paymentId}", paymentId).with(jwtWithRole("ADMIN")))
        .andExpect(status().isOk());
  }

  @Test
  void customerCannotReadPaymentStatus() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/payments/{paymentId}", UUID.randomUUID()).with(jwtWithRole("CUSTOMER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void unauthenticatedCannotReadPaymentStatus() throws Exception {
    mockMvc
        .perform(get("/api/v1/payments/{paymentId}", UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void operatorCanReadAttempts() throws Exception {
    UUID paymentId = UUID.randomUUID();
    when(paymentQueryService.findAttempts(paymentId)).thenReturn(List.of());

    mockMvc
        .perform(
            get("/api/v1/payments/{paymentId}/attempts", paymentId).with(jwtWithRole("OPERATOR")))
        .andExpect(status().isOk());
  }

  @Test
  void operatorCanRefundAPayment() throws Exception {
    UUID paymentId = UUID.randomUUID();
    when(refundService.refund(any(), any(), any(), any(), any()))
        .thenReturn(sampleRefund(paymentId));

    mockMvc
        .perform(
            post("/api/v1/payments/{paymentId}/refunds", paymentId)
                .with(jwtWithRole("OPERATOR"))
                .header("Idempotency-Key", "refund-1")
                .contentType("application/json")
                .content(REFUND_BODY))
        .andExpect(status().isCreated());
  }

  @Test
  void customerCannotRefundAPayment() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/payments/{paymentId}/refunds", UUID.randomUUID())
                .with(jwtWithRole("CUSTOMER"))
                .header("Idempotency-Key", "refund-1")
                .contentType("application/json")
                .content(REFUND_BODY))
        .andExpect(status().isForbidden());
  }

  @Test
  void refundingWithoutAnIdempotencyKeyIsRejected() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/payments/{paymentId}/refunds", UUID.randomUUID())
                .with(jwtWithRole("OPERATOR"))
                .contentType("application/json")
                .content(REFUND_BODY))
        .andExpect(status().isBadRequest());
  }

  private static JwtRequestPostProcessor jwtWithRole(String role) {
    return jwt()
        .jwt(token -> token.subject(UUID.randomUUID().toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_" + role));
  }

  private static Payment samplePayment(UUID paymentId) {
    return Payment.authorized(
        paymentId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        new BigDecimal("25.00"),
        "USD",
        UUID.randomUUID());
  }

  private static Refund sampleRefund(UUID paymentId) {
    return new Refund(
        UUID.randomUUID(),
        paymentId,
        new BigDecimal("25.00"),
        "USD",
        "FULFILLMENT_CANCELLED",
        UUID.randomUUID());
  }
}
