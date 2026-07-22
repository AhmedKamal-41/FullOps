package com.ahmedali.fulfillops.order.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ahmedali.fulfillops.order.config.SecurityConfig;
import com.ahmedali.fulfillops.order.config.TestSecurityConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Drives /api/v1/whoami through real Spring Security filters using test-issued JWTs
 * (spring-security-test's jwt() request post-processor) instead of a running Keycloak.
 */
@WebMvcTest(WhoAmIController.class)
@Import({SecurityConfig.class, TestSecurityConfig.class})
class WhoAmIAuthorizationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void rejectsRequestsWithNoToken() throws Exception {
    mockMvc.perform(get("/api/v1/whoami")).andExpect(status().isUnauthorized());
  }

  @Test
  void returnsSubjectUsernameAndRealmRoleForAnAuthenticatedCustomer() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/whoami")
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .subject("customer-demo-subject")
                                    .claim("preferred_username", "customer.demo")
                                    .claim("realm_access", Map.of("roles", List.of("CUSTOMER"))))
                        .authorities(SecurityConfig::realmRolesAsAuthorities)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subject").value("customer-demo-subject"))
        .andExpect(jsonPath("$.username").value("customer.demo"))
        .andExpect(jsonPath("$.roles[0]").value("ROLE_CUSTOMER"));
  }

  // The ops console is a browser SPA on a different origin — without a real CORS
  // preflight response, the browser blocks every request before Spring Security even sees it,
  // no matter how the token/role check would have gone. This drives an actual OPTIONS preflight
  // through the real filter chain rather than just asserting a bean exists.
  @Test
  void respondsToAPreflightRequestFromTheOpsConsoleOrigin() throws Exception {
    mockMvc
        .perform(
            options("/api/v1/whoami")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
  }
}
