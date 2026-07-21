package com.ahmedali.fulfillops.order.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ahmedali.fulfillops.order.config.SecurityConfig;
import com.ahmedali.fulfillops.order.config.TestSecurityConfig;
import com.ahmedali.fulfillops.order.domain.ProjectionRebuildRun;
import com.ahmedali.fulfillops.order.domain.ProjectionRebuildRunRepository;
import com.ahmedali.fulfillops.order.service.OperationsProjectionRebuildService;
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
 * Rebuild is ADMIN-only — even OPERATOR (who can read every other /api/v1/ops/** endpoint) must be
 * rejected.
 */
@WebMvcTest(ProjectionRebuildController.class)
@Import({SecurityConfig.class, TestSecurityConfig.class, GlobalExceptionHandler.class})
class ProjectionRebuildControllerAuthorizationTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private OperationsProjectionRebuildService rebuildService;
  @MockitoBean private ProjectionRebuildRunRepository rebuildRunRepository;

  @Test
  void adminCanTriggerARebuild() throws Exception {
    when(rebuildService.rebuild(any())).thenReturn(new ProjectionRebuildRun("admin-1"));

    mockMvc
        .perform(post("/api/v1/admin/operations-projection/rebuild").with(jwtWithRole("ADMIN")))
        .andExpect(status().isOk());
  }

  @Test
  void operatorCannotTriggerARebuild() throws Exception {
    mockMvc
        .perform(post("/api/v1/admin/operations-projection/rebuild").with(jwtWithRole("OPERATOR")))
        .andExpect(status().isForbidden());
  }

  @Test
  void unauthenticatedCannotTriggerARebuild() throws Exception {
    mockMvc
        .perform(post("/api/v1/admin/operations-projection/rebuild"))
        .andExpect(status().isUnauthorized());
  }

  private static JwtRequestPostProcessor jwtWithRole(String role) {
    return jwt()
        .jwt(token -> token.subject(UUID.randomUUID().toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_" + role));
  }
}
