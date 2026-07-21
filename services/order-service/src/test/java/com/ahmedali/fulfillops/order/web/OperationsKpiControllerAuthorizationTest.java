package com.ahmedali.fulfillops.order.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ahmedali.fulfillops.order.config.SecurityConfig;
import com.ahmedali.fulfillops.order.config.TestSecurityConfig;
import com.ahmedali.fulfillops.order.domain.LowStockSignalRepository;
import com.ahmedali.fulfillops.order.service.KpiOverviewService;
import com.ahmedali.fulfillops.order.service.KpiTimeSeriesService;
import com.ahmedali.fulfillops.order.service.StageDurationKpiService;
import com.ahmedali.fulfillops.order.web.dto.BacklogResponse;
import com.ahmedali.fulfillops.order.web.dto.KpiOverviewResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OperationsKpiController.class)
@Import({SecurityConfig.class, TestSecurityConfig.class, GlobalExceptionHandler.class})
class OperationsKpiControllerAuthorizationTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private KpiOverviewService overviewService;
  @MockitoBean private KpiTimeSeriesService timeSeriesService;
  @MockitoBean private StageDurationKpiService stageDurationKpiService;
  @MockitoBean private LowStockSignalRepository lowStockSignalRepository;

  @Test
  void operatorCanReadTheOverview() throws Exception {
    when(overviewService.overview(any(), any()))
        .thenReturn(
            new KpiOverviewResponse(
                Instant.EPOCH,
                Instant.now(),
                0,
                0,
                0,
                0,
                0,
                List.of(),
                0,
                0,
                List.of(),
                0,
                0,
                0,
                null,
                0,
                null,
                0,
                0));

    mockMvc
        .perform(
            get("/api/v1/ops/kpis/overview")
                .param("from", "2026-01-01T00:00:00Z")
                .param("to", "2026-01-02T00:00:00Z")
                .with(jwtWithRole("OPERATOR")))
        .andExpect(status().isOk());
  }

  @Test
  void aFromAfterToIsRejectedWithBadRequest() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/ops/kpis/overview")
                .param("from", "2026-01-02T00:00:00Z")
                .param("to", "2026-01-01T00:00:00Z")
                .with(jwtWithRole("OPERATOR")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void customerCannotReadTheOverview() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/ops/kpis/overview")
                .param("from", "2026-01-01T00:00:00Z")
                .param("to", "2026-01-02T00:00:00Z")
                .with(jwtWithRole("CUSTOMER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void unauthenticatedCannotReadTheBacklog() throws Exception {
    mockMvc.perform(get("/api/v1/ops/backlog")).andExpect(status().isUnauthorized());
  }

  @Test
  void operatorCanReadTheBacklog() throws Exception {
    when(stageDurationKpiService.backlog()).thenReturn(new BacklogResponse(List.of()));

    mockMvc
        .perform(get("/api/v1/ops/backlog").with(jwtWithRole("OPERATOR")))
        .andExpect(status().isOk());
  }

  private static JwtRequestPostProcessor jwtWithRole(String role) {
    return jwt()
        .jwt(token -> token.subject(UUID.randomUUID().toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_" + role));
  }
}
