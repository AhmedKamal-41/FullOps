package com.ahmedali.fulfillops.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ahmedali.fulfillops.order.config.TestSecurityConfig;
import com.ahmedali.fulfillops.order.config.TestcontainersConfiguration;
import com.ahmedali.fulfillops.order.web.dto.CreateOrderItemRequest;
import com.ahmedali.fulfillops.order.web.dto.CreateOrderRequest;
import com.ahmedali.fulfillops.order.web.dto.MoneyDto;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the "insert-then-catch-unique-violation" design (see OrderCreationTransaction /
 * OrderService) actually holds up under real concurrent submission, not just sequential replay. N
 * threads race to submit the exact same customer, Idempotency-Key, and payload at (as close to) the
 * same instant as a CountDownLatch allows; exactly one of them must win the database's unique
 * constraint on (customer_id, idempotency_key), and every response — winner and losers alike — must
 * carry the same orderId back to the caller.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class OrderConcurrencyIT {

  private static final int CONCURRENT_REQUESTS = 10;

  @Autowired private MockMvc mockMvc;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private ObjectMapper objectMapper;

  @Test
  void concurrentIdenticalSubmissionsCreateExactlyOneOrder() throws Exception {
    UUID customerId = UUID.randomUUID();
    String idempotencyKey = "checkout-" + UUID.randomUUID();
    String body = objectMapper.writeValueAsString(sampleRequest());

    CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
    try {
      List<Future<UUID>> futures =
          IntStream.range(0, CONCURRENT_REQUESTS)
              .<Future<UUID>>mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            ready.countDown();
                            go.await();
                            return submitOrder(customerId, idempotencyKey, body);
                          }))
              .toList();

      ready.await();
      go.countDown();

      Set<UUID> returnedOrderIds =
          futures.stream()
              .map(
                  future -> {
                    try {
                      return future.get();
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  })
              .collect(Collectors.toSet());

      assertThat(returnedOrderIds)
          .as("every concurrent submission must be told about the same order")
          .hasSize(1);
      assertThat(countRows("orders", "customer_id = ?", customerId)).isEqualTo(1);
      assertThat(countRows("idempotency_requests", "customer_id = ?", customerId)).isEqualTo(1);
    } finally {
      pool.shutdown();
    }
  }

  private UUID submitOrder(UUID customerId, String idempotencyKey, String body) throws Exception {
    String response =
        mockMvc
            .perform(
                post("/api/v1/orders")
                    .with(customer(customerId))
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(objectMapper.readTree(response).get("orderId").asString());
  }

  private static RequestPostProcessor customer(UUID customerId) {
    return SecurityMockMvcRequestPostProcessors.jwt()
        .jwt(token -> token.subject(customerId.toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
  }

  private CreateOrderRequest sampleRequest() {
    return new CreateOrderRequest(
        List.of(new CreateOrderItemRequest("WIDGET-BLUE-M", 2, new MoneyDto("USD", "19.99"))));
  }

  private int countRows(String table, String where, Object param) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE " + where, Integer.class, param);
    return count == null ? 0 : count;
  }
}
