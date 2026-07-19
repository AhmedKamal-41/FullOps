package com.ahmedali.fulfillops.inventory.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ahmedali.fulfillops.inventory.config.TestSecurityConfig;
import com.ahmedali.fulfillops.inventory.config.TestcontainersConfiguration;
import com.redis.testcontainers.RedisContainer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Kills the Testcontainers Redis mid-test to prove the "must remain correct if Redis is
 * unavailable" requirement: an availability read still succeeds, served from PostgreSQL, and the
 * outage is only visible as an incremented failure counter — never an exception the caller
 * sees. @DirtiesContext at the class level forces a fresh context (and fresh containers) for
 * whatever test runs next, since this class deliberately leaves its own Redis container dead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
@DirtiesContext
class InventoryAvailabilityRedisOutageIT {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private RedisContainer redisContainer;

  @Autowired private MeterRegistry meterRegistry;

  @Test
  void availabilityReadsFallBackToPostgresWhenRedisIsDown() throws Exception {
    String sku = "SKU-OUTAGE-" + UUID.randomUUID();
    createProduct(sku);
    adjustStock(sku, "{\"changeQuantity\": 7, \"reasonCode\": \"RESTOCK\"}");

    // Warm the cache once while Redis is healthy, then take it down.
    mockMvc.perform(get("/api/v1/inventory/" + sku).with(operator())).andExpect(status().isOk());
    redisContainer.stop();

    double failuresBefore = cacheFailureCount();

    mockMvc
        .perform(get("/api/v1/inventory/" + sku).with(operator()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.availableQuantity").value(7));

    assertThat(cacheFailureCount())
        .as("a Redis outage must surface as a metric, not an exception")
        .isGreaterThan(failuresBefore);
  }

  private double cacheFailureCount() {
    Counter counter = meterRegistry.find("inventory.cache.failures").counter();
    return counter == null ? 0 : counter.count();
  }

  private void createProduct(String sku) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/products")
                .with(admin())
                .header("Idempotency-Key", "create-" + UUID.randomUUID())
                .contentType("application/json")
                .content(
                    objectMapper.writeValueAsString(
                        Map.of(
                            "sku", sku, "name", "Test " + sku, "description", "a test product"))))
        .andExpect(status().isCreated());
  }

  private void adjustStock(String sku, String body) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/inventory/" + sku + "/adjustments")
                .with(admin())
                .header("Idempotency-Key", "adjust-" + UUID.randomUUID())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isCreated());
  }

  private static JwtRequestPostProcessor admin() {
    return jwt()
        .jwt(token -> token.subject(UUID.randomUUID().toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  private static JwtRequestPostProcessor operator() {
    return jwt()
        .jwt(token -> token.subject(UUID.randomUUID().toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"));
  }
}
