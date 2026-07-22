package com.ahmedali.fulfillops.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ahmedali.fulfillops.order.config.TestSecurityConfig;
import com.ahmedali.fulfillops.order.config.TestcontainersConfiguration;
import com.ahmedali.fulfillops.order.domain.Order;
import com.ahmedali.fulfillops.order.domain.OrderOperationsProjection;
import com.ahmedali.fulfillops.order.domain.OrderOperationsProjectionRepository;
import com.ahmedali.fulfillops.order.domain.OrderRepository;
import com.ahmedali.fulfillops.order.domain.OrderStageDuration;
import com.ahmedali.fulfillops.order.domain.OrderStageDurationRepository;
import com.ahmedali.fulfillops.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Confirms the work-queue/backlog and stage-duration-percentile queries actually use the indexes
 * V4__operations.sql defines, on a realistically seeded dataset — a real, checkable assertion
 * (EXPLAIN's own plan output), never a fabricated latency or throughput number (this project forbids
 * publishing those without a command in this repo actually producing them).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class OperationsQueryPlanIT {

  // Large enough, and split across enough distinct stage values, that PICKING is a small minority
  // of rows — an index only ever beats a sequential scan when the filter is actually selective;
  // seeding every row with the same stage (100% selectivity) would make a Seq Scan the objectively
  // correct, cheaper plan regardless of table size, which was the first version of this test's own
  // bug, caught by actually running it and reading the real EXPLAIN output rather than assuming.
  private static final int SEEDED_ORDER_COUNT = 5000;

  @Autowired private OrderRepository orderRepository;
  @Autowired private OrderOperationsProjectionRepository projectionRepository;
  @Autowired private OrderStageDurationRepository stageDurationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void theWorkQueueStatusFilterUsesAnIndexScanNotASequentialScan() {
    seedRealisticData();

    List<String> plan =
        jdbcTemplate.queryForList(
            "EXPLAIN SELECT * FROM order_operations_projection WHERE status = 'PICKING'",
            String.class);

    String planText = String.join("\n", plan);
    assertThat(planText)
        .as("query plan:\n%s", planText)
        .containsIgnoringCase("idx_ops_projection_status");
  }

  @Test
  void theStageDurationPercentileQueryUsesAnIndexScanNotASequentialScan() {
    seedRealisticData();

    List<String> plan =
        jdbcTemplate.queryForList(
            """
            EXPLAIN SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY duration_seconds)
            FROM order_stage_duration
            WHERE stage = 'PICKING' AND exited_at BETWEEN '2020-01-01' AND '2030-01-01'
            """,
            String.class);

    String planText = String.join("\n", plan);
    assertThat(planText)
        .as("query plan:\n%s", planText)
        .containsIgnoringCase("idx_stage_duration_stage_exited_at");
  }

  private void seedRealisticData() {
    Instant now = Instant.now();
    for (int i = 0; i < SEEDED_ORDER_COUNT; i++) {
      Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "USD", new BigDecimal("10.00"));
      orderRepository.save(order);

      OrderStatus status = OrderStatus.values()[i % OrderStatus.values().length];
      OrderOperationsProjection projection =
          new OrderOperationsProjection(
              order.getOrderId(),
              order.getCustomerId(),
              status,
              order.getCurrencyCode(),
              order.getTotalAmount(),
              order.getCreatedAt());
      projectionRepository.save(projection);

      OrderStageDuration stageDuration =
          new OrderStageDuration(order.getOrderId(), status, now.minusSeconds(3600));
      stageDuration.close(now);
      stageDurationRepository.save(stageDuration);
    }
    // Postgres's planner only prefers an index scan once it has real statistics — ANALYZE is what
    // a production deployment's autovacuum would eventually do on its own.
    jdbcTemplate.execute("ANALYZE order_operations_projection");
    jdbcTemplate.execute("ANALYZE order_stage_duration");
  }
}
