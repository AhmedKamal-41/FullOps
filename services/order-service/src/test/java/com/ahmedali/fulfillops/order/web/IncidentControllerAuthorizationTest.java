package com.ahmedali.fulfillops.order.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ahmedali.fulfillops.order.config.SecurityConfig;
import com.ahmedali.fulfillops.order.config.TestSecurityConfig;
import com.ahmedali.fulfillops.order.domain.OperationsIncidentRepository;
import com.ahmedali.fulfillops.order.service.IncidentActionService;
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

@WebMvcTest(IncidentController.class)
@Import({SecurityConfig.class, TestSecurityConfig.class, GlobalExceptionHandler.class})
class IncidentControllerAuthorizationTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private OperationsIncidentRepository incidentRepository;
  @MockitoBean private IncidentActionService incidentActionService;

  @Test
  void operatorCanListIncidents() throws Exception {
    when(incidentRepository.findAll(
            any(org.springframework.data.jpa.domain.Specification.class),
            any(org.springframework.data.domain.Pageable.class)))
        .thenReturn(Page.empty());

    mockMvc
        .perform(get("/api/v1/ops/incidents").with(jwtWithRole("OPERATOR")))
        .andExpect(status().isOk());
  }

  @Test
  void customerCannotListIncidents() throws Exception {
    mockMvc
        .perform(get("/api/v1/ops/incidents").with(jwtWithRole("CUSTOMER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void unauthenticatedCannotListIncidents() throws Exception {
    mockMvc.perform(get("/api/v1/ops/incidents")).andExpect(status().isUnauthorized());
  }

  @Test
  void operatorCanAcknowledgeAnIncident() throws Exception {
    UUID incidentId = UUID.randomUUID();
    when(incidentActionService.acknowledge(any(), any())).thenReturn(sampleIncident());

    mockMvc
        .perform(
            post("/api/v1/ops/incidents/{incidentId}/acknowledge", incidentId)
                .with(jwtWithRole("OPERATOR")))
        .andExpect(status().isOk());
  }

  @Test
  void customerCannotAcknowledgeAnIncident() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/ops/incidents/{incidentId}/acknowledge", UUID.randomUUID())
                .with(jwtWithRole("CUSTOMER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void resolvingWithoutABodyIsAllowed() throws Exception {
    UUID incidentId = UUID.randomUUID();
    when(incidentActionService.resolve(any(), any(), any())).thenReturn(sampleIncident());

    mockMvc
        .perform(
            post("/api/v1/ops/incidents/{incidentId}/resolve", incidentId)
                .with(jwtWithRole("ADMIN")))
        .andExpect(status().isOk());
  }

  @Test
  void assigningRequiresAnAssigneeInTheBody() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/ops/incidents/{incidentId}/assign", UUID.randomUUID())
                .with(jwtWithRole("OPERATOR"))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  private static com.ahmedali.fulfillops.order.domain.OperationsIncident sampleIncident() {
    return new com.ahmedali.fulfillops.order.domain.OperationsIncident(
        UUID.randomUUID(),
        com.ahmedali.fulfillops.order.domain.IncidentKind.CANCELLATION_STUCK,
        "test");
  }

  private static JwtRequestPostProcessor jwtWithRole(String role) {
    return jwt()
        .jwt(token -> token.subject(UUID.randomUUID().toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_" + role));
  }
}
