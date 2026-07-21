package com.ahmedali.fulfillops.order.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ahmedali.fulfillops.order.config.SecurityConfig;
import com.ahmedali.fulfillops.order.config.TestSecurityConfig;
import com.ahmedali.fulfillops.order.service.OrderTimelineService;
import com.ahmedali.fulfillops.order.service.WorkQueueService;
import com.ahmedali.fulfillops.order.web.dto.OrderTimelineResponse;
import java.util.List;
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
 * The OPERATOR/ADMIN-vs-CUSTOMER-vs-unauthenticated matrix for the work queue and timeline
 * endpoints — same jwt()-based technique as OrderControllerAuthorizationTest.
 */
@WebMvcTest(WorkQueueController.class)
@Import({SecurityConfig.class, TestSecurityConfig.class, GlobalExceptionHandler.class})
class WorkQueueControllerAuthorizationTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private WorkQueueService workQueueService;
  @MockitoBean private OrderTimelineService timelineService;
  @MockitoBean private WorkQueueCsvWriter csvWriter;

  @Test
  void operatorCanListTheWorkQueue() throws Exception {
    when(workQueueService.search(any(), any())).thenReturn(Page.empty());

    mockMvc
        .perform(get("/api/v1/ops/work-queue").with(jwtWithRole("OPERATOR")))
        .andExpect(status().isOk());
  }

  @Test
  void adminCanListTheWorkQueue() throws Exception {
    when(workQueueService.search(any(), any())).thenReturn(Page.empty());

    mockMvc
        .perform(get("/api/v1/ops/work-queue").with(jwtWithRole("ADMIN")))
        .andExpect(status().isOk());
  }

  @Test
  void customerCannotListTheWorkQueue() throws Exception {
    mockMvc
        .perform(get("/api/v1/ops/work-queue").with(jwtWithRole("CUSTOMER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void unauthenticatedCannotListTheWorkQueue() throws Exception {
    mockMvc.perform(get("/api/v1/ops/work-queue")).andExpect(status().isUnauthorized());
  }

  @Test
  void customerCannotExportTheWorkQueue() throws Exception {
    mockMvc
        .perform(get("/api/v1/ops/work-queue/export").with(jwtWithRole("CUSTOMER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void customerCannotViewAnOrderTimeline() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/ops/orders/{orderId}/timeline", UUID.randomUUID())
                .with(jwtWithRole("CUSTOMER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void operatorCanViewAnOrderTimeline() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(timelineService.timelineFor(orderId))
        .thenReturn(new OrderTimelineResponse(orderId, List.of()));

    mockMvc
        .perform(
            get("/api/v1/ops/orders/{orderId}/timeline", orderId).with(jwtWithRole("OPERATOR")))
        .andExpect(status().isOk());
  }

  private static JwtRequestPostProcessor jwtWithRole(String role) {
    return jwt()
        .jwt(token -> token.subject(UUID.randomUUID().toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_" + role));
  }
}
