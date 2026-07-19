package com.ahmedali.fulfillops.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ahmedali.fulfillops.inventory.config.TestSecurityConfig;
import com.ahmedali.fulfillops.inventory.config.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives the real product/inventory endpoints through MockMvc against Testcontainers Postgres,
 * asserting both the HTTP response and what actually committed to the database — the same technique
 * order-service's OrderCreationIT uses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class InventoryControllerIT {

  @Autowired private MockMvc mockMvc;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private ObjectMapper objectMapper;

  @Test
  void creatingAProductAlsoCreatesAZeroStockRow() throws Exception {
    String sku = uniqueSku("CREATE");
    createProduct(sku, "create-" + UUID.randomUUID());

    mockMvc
        .perform(get("/api/v1/inventory/" + sku).with(operator()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.availableQuantity").value(0))
        .andExpect(jsonPath("$.reservedQuantity").value(0));
  }

  @Test
  void replayingProductCreationWithTheSameIdempotencyKeyReturnsTheOriginalProduct()
      throws Exception {
    // Idempotency is scoped per actor (see IdempotencyRequestId), so both calls must come from
    // the same admin subject for this to be a genuine replay rather than two different admins
    // racing to claim the same sku.
    JwtRequestPostProcessor sameAdmin = admin();
    String sku = uniqueSku("REPLAY");
    String idempotencyKey = "create-" + UUID.randomUUID();

    String first = createProduct(sku, idempotencyKey, sameAdmin);
    String second = createProduct(sku, idempotencyKey, sameAdmin);

    assertThat(productId(second)).isEqualTo(productId(first));
    assertThat(countRows("product", "sku = ?", sku)).isEqualTo(1);
  }

  @Test
  void adjustingStockWithAnUnknownReasonCodeIsRejected() throws Exception {
    String sku = uniqueSku("BADREASON");
    createProduct(sku, "create-" + UUID.randomUUID());

    mockMvc
        .perform(
            post("/api/v1/inventory/" + sku + "/adjustments")
                .with(admin())
                .header("Idempotency-Key", "adjust-" + UUID.randomUUID())
                .contentType("application/json")
                .content("{\"changeQuantity\": 10, \"reasonCode\": \"NOT_REAL\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void adjustingStockTwiceWithTheSameIdempotencyKeyAppliesItOnlyOnce() throws Exception {
    // Same reasoning as the product-replay test: idempotency is scoped per actor, so both
    // adjustment calls must come from the same admin subject.
    JwtRequestPostProcessor sameAdmin = admin();
    String sku = uniqueSku("ADJUST-IDEMPOTENT");
    createProduct(sku, "create-" + UUID.randomUUID(), sameAdmin);
    String idempotencyKey = "adjust-" + UUID.randomUUID();
    String body =
        "{\"changeQuantity\": 10, \"reasonCode\": \"RESTOCK\", \"reasonDetail\": \"restock\"}";

    adjustStock(sku, idempotencyKey, body, sameAdmin);
    adjustStock(sku, idempotencyKey, body, sameAdmin);

    assertThat(countRows("inventory_adjustment", "sku = ?", sku)).isEqualTo(1);
    mockMvc
        .perform(get("/api/v1/inventory/" + sku).with(operator()))
        .andExpect(jsonPath("$.availableQuantity").value(10));
  }

  @Test
  void lowStockListingOnlyReturnsSkusAtOrBelowTheThreshold() throws Exception {
    String lowSku = uniqueSku("LOW");
    String highSku = uniqueSku("HIGH");
    createProduct(lowSku, "create-" + UUID.randomUUID());
    createProduct(highSku, "create-" + UUID.randomUUID());
    adjustStock(
        lowSku,
        "adjust-" + UUID.randomUUID(),
        "{\"changeQuantity\": 2, \"reasonCode\": \"RESTOCK\"}");
    adjustStock(
        highSku,
        "adjust-" + UUID.randomUUID(),
        "{\"changeQuantity\": 500, \"reasonCode\": \"RESTOCK\"}");

    String response =
        mockMvc
            .perform(get("/api/v1/inventory/low-stock").param("threshold", "5").with(operator()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(skusIn(response)).contains(lowSku).doesNotContain(highSku);
  }

  private String createProduct(String sku, String idempotencyKey) throws Exception {
    return createProduct(sku, idempotencyKey, admin());
  }

  private String createProduct(String sku, String idempotencyKey, JwtRequestPostProcessor actor)
      throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/products")
                .with(actor)
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .content(
                    objectMapper.writeValueAsString(
                        Map.of(
                            "sku", sku, "name", "Test " + sku, "description", "a test product"))))
        .andExpect(status().isCreated())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private void adjustStock(String sku, String idempotencyKey, String body) throws Exception {
    adjustStock(sku, idempotencyKey, body, admin());
  }

  private void adjustStock(
      String sku, String idempotencyKey, String body, JwtRequestPostProcessor actor)
      throws Exception {
    mockMvc
        .perform(
            post("/api/v1/inventory/" + sku + "/adjustments")
                .with(actor)
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isCreated());
  }

  private String productId(String responseJson) {
    return objectMapper.readTree(responseJson).get("productId").asString();
  }

  private List<String> skusIn(String listResponseJson) {
    List<String> skus = new ArrayList<>();
    for (JsonNode item : objectMapper.readTree(listResponseJson)) {
      skus.add(item.get("sku").asString());
    }
    return skus;
  }

  private static String uniqueSku(String label) {
    return "SKU-" + label + "-" + UUID.randomUUID();
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

  private int countRows(String table, String where, Object param) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE " + where, Integer.class, param);
    return count == null ? 0 : count;
  }
}
