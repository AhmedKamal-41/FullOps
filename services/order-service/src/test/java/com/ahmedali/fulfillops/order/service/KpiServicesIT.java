package com.ahmedali.fulfillops.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import com.ahmedali.fulfillops.order.config.TestSecurityConfig;
import com.ahmedali.fulfillops.order.config.TestcontainersConfiguration;
import com.ahmedali.fulfillops.order.domain.Order;
import com.ahmedali.fulfillops.order.domain.OrderOperationsProjection;
import com.ahmedali.fulfillops.order.domain.OrderOperationsProjectionRepository;
import com.ahmedali.fulfillops.order.domain.OrderRepository;
import com.ahmedali.fulfillops.order.domain.OrderStageDuration;
import com.ahmedali.fulfillops.order.domain.OrderStageDurationRepository;
import com.ahmedali.fulfillops.order.domain.OrderStatus;
import com.ahmedali.fulfillops.order.domain.TimeSeriesInterval;
import com.ahmedali.fulfillops.order.web.dto.BacklogResponse;
import com.ahmedali.fulfillops.order.web.dto.KpiOverviewResponse;
import com.ahmedali.fulfillops.order.web.dto.KpiTimeSeriesResponse;
import com.ahmedali.fulfillops.order.web.dto.ReasonCodeCountResponse;
import com.ahmedali.fulfillops.order.web.dto.StageBacklogResponse;
import com.ahmedali.fulfillops.order.web.dto.StageDurationKpiResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * KPI formula correctness against known seeded data, run against real Postgres (Testcontainers) so
 * the Postgres-specific bits (percentile_cont, date_trunc) are actually exercised, not mocked away.
 * Every formula asserted here matches docs/KPI_DICTIONARY.md exactly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
class KpiServicesIT {

  @Autowired private OrderRepository orderRepository;
  @Autowired private OrderOperationsProjectionRepository projectionRepository;
  @Autowired private OrderStageDurationRepository stageDurationRepository;
  @Autowired private KpiOverviewService overviewService;
  @Autowired private KpiTimeSeriesService timeSeriesService;
  @Autowired private StageDurationKpiService stageDurationKpiService;

  @Test
  void anEmptyWindowReturnsAllZerosNeverNaNOrAnError() {
    Instant from = Instant.parse("2020-01-01T00:00:00Z");
    Instant to = Instant.parse("2020-01-02T00:00:00Z");

    KpiOverviewResponse response = overviewService.overview(from, to);

    assertThat(response.ordersReceived()).isZero();
    assertThat(response.inventoryRejectionRate()).isZero();
    assertThat(response.paymentDeclineRate()).isZero();
    assertThat(response.paymentTechnicalFailureRate()).isZero();
    assertThat(response.recoverySuccessRate()).isZero();
    assertThat(response.manualTouchRate()).isZero();
  }

  @Test
  void overviewCountsAndRatesMatchThreeSeededOrdersExactly() {
    // A fixed, test-only instant far from "now" and from every other test's own fixed window
    // (e.g. the time-series test's Feb 2026 dates) — running the whole suite together means every
    // IT shares one real Testcontainers Postgres with no per-test rollback, so a window anchored
    // to Instant.now() picks up other tests' concurrently-seeded rows too. Caught by actually
    // running the full module verify, not just this class in isolation, where it passed.
    Instant base = Instant.parse("2025-03-15T00:00:00Z");
    Instant windowStart = base.minusSeconds(3600);
    Instant windowEnd = base.plusSeconds(3600);

    Order delivered = seedOrder();
    seedProjectionAt(delivered, OrderStatus.DELIVERED, base, null, null, 0);

    Order declined = seedOrder();
    seedProjectionAt(
        declined, OrderStatus.CANCELLED, base, null, "SIMULATED_INSUFFICIENT_FUNDS", 2);

    Order rejected = seedOrder();
    seedProjectionAt(rejected, OrderStatus.CANCELLED, base, "INSUFFICIENT_STOCK", null, 0);

    KpiOverviewResponse response = overviewService.overview(windowStart, windowEnd);

    assertThat(response.ordersReceived()).isEqualTo(3);
    assertThat(response.ordersCompleted()).isEqualTo(1);
    assertThat(response.ordersCancelled()).isEqualTo(2);

    assertThat(response.inventoryRejections()).isEqualTo(1);
    assertThat(response.inventoryRejectionRate()).isCloseTo(1.0 / 3, offset(0.0001));
    assertThat(response.inventoryRejectionReasons())
        .containsExactly(new ReasonCodeCountResponse("INSUFFICIENT_STOCK", 1));

    // Payment-eligible = orders that were never inventory-rejected: 2 of the 3.
    assertThat(response.paymentDeclines()).isEqualTo(1);
    assertThat(response.paymentDeclineRate()).isCloseTo(1.0 / 2, offset(0.0001));
    assertThat(response.paymentDeclineReasons())
        .containsExactly(new ReasonCodeCountResponse("SIMULATED_INSUFFICIENT_FUNDS", 1));
    assertThat(response.paymentTechnicalFailureRate()).isCloseTo(1.0 / 2, offset(0.0001));
  }

  @Test
  void stageDurationPercentilesMatchAKnownThreeSampleDataset() {
    // See overviewCountsAndRatesMatchThreeSeededOrdersExactly for why this uses a fixed,
    // suite-unique window rather than one anchored to Instant.now().
    Instant base = Instant.parse("2025-03-20T00:00:00Z");
    Instant from = base.minusSeconds(3600);
    Instant to = base.plusSeconds(3600);

    // percentile_cont(0.5) over {10, 20, 30} is exactly the middle value, 20.
    seedClosedStageDurationAt(OrderStatus.PICKING, base, 10);
    seedClosedStageDurationAt(OrderStatus.PICKING, base, 20);
    seedClosedStageDurationAt(OrderStatus.PICKING, base, 30);

    StageDurationKpiResponse response = stageDurationKpiService.stageDurations(from, to);

    var picking =
        response.stages().stream()
            .filter(s -> s.stage().equals("PICKING"))
            .findFirst()
            .orElseThrow();
    assertThat(picking.sampleCount()).isEqualTo(3);
    assertThat(picking.p50Seconds()).isEqualTo(20.0);
  }

  @Test
  void timeSeriesBucketsOrdersByTheirCreatedAtDay() {
    Instant dayOneStart = Instant.parse("2026-02-01T00:00:00Z").plusSeconds(3600);
    Instant dayTwoStart = Instant.parse("2026-02-02T00:00:00Z").plusSeconds(3600);
    seedOrderBackdated(dayOneStart);
    seedOrderBackdated(dayOneStart.plusSeconds(60));
    seedOrderBackdated(dayTwoStart);

    KpiTimeSeriesResponse response =
        timeSeriesService.timeSeries(
            Instant.parse("2026-02-01T00:00:00Z"),
            Instant.parse("2026-02-03T00:00:00Z"),
            TimeSeriesInterval.DAY);

    assertThat(response.points()).hasSize(2);
    assertThat(response.points().get(0).ordersReceived()).isEqualTo(2);
    assertThat(response.points().get(1).ordersReceived()).isEqualTo(1);
  }

  @Test
  void backlogCountsOpenOrdersPerStageAndFlagsSlaBreaches() {
    // app.ops.sla.stage-thresholds.PICKING is PT4H (application.yml) — an order that has been in
    // PICKING for 5 hours is breached, one that entered 1 hour ago is not.
    Order breached = seedOrder();
    seedProjectionAtStage(breached, OrderStatus.PICKING, Instant.now().minus(5, ChronoUnit.HOURS));
    Order notBreached = seedOrder();
    seedProjectionAtStage(
        notBreached, OrderStatus.PICKING, Instant.now().minus(1, ChronoUnit.HOURS));

    BacklogResponse response = stageDurationKpiService.backlog();

    StageBacklogResponse picking =
        response.stages().stream()
            .filter(s -> s.stage().equals("PICKING"))
            .findFirst()
            .orElseThrow();
    assertThat(picking.openOrderCount()).isGreaterThanOrEqualTo(2);
    assertThat(picking.slaBreachedCount()).isGreaterThanOrEqualTo(1);
  }

  private Order seedOrder() {
    Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "USD", new BigDecimal("10.00"));
    return orderRepository.save(order);
  }

  // OrderOperationsProjection.createdAt is an explicit constructor parameter (unlike Order's own
  // createdAt, always Instant.now() internally) — no JDBC backdating trick needed, just build the
  // projection row with the timestamp the test wants directly.
  private void seedOrderBackdated(Instant createdAt) {
    Order order = seedOrder();
    OrderOperationsProjection projection =
        new OrderOperationsProjection(
            order.getOrderId(),
            order.getCustomerId(),
            OrderStatus.PENDING,
            order.getCurrencyCode(),
            order.getTotalAmount(),
            createdAt);
    projectionRepository.save(projection);
  }

  // createdAt is explicit (not order.getCreatedAt(), which is always Instant.now() from Order's
  // own constructor) so this row lands in a fixed, test-chosen window instead of "whenever this
  // test happened to run" — see the callers' comments for why that matters when the whole module
  // shares one real, non-rolled-back Postgres across every IT class.
  private void seedProjectionAt(
      Order order,
      OrderStatus status,
      Instant createdAt,
      String inventoryRejectionReasonCode,
      String paymentDeclineReasonCode,
      int paymentTechnicalFailureCount) {
    OrderOperationsProjection projection =
        new OrderOperationsProjection(
            order.getOrderId(),
            order.getCustomerId(),
            status,
            order.getCurrencyCode(),
            order.getTotalAmount(),
            createdAt);
    projection.advanceStage(status, null, createdAt);
    projection.recordInventoryRejection(inventoryRejectionReasonCode);
    projection.recordPaymentOutcome(paymentDeclineReasonCode, paymentTechnicalFailureCount);
    projectionRepository.save(projection);
  }

  private void seedProjectionAtStage(
      Order order, OrderStatus stage, Instant currentStageEnteredAt) {
    OrderOperationsProjection projection =
        new OrderOperationsProjection(
            order.getOrderId(),
            order.getCustomerId(),
            stage,
            order.getCurrencyCode(),
            order.getTotalAmount(),
            order.getCreatedAt());
    projection.advanceStage(stage, null, currentStageEnteredAt);
    projectionRepository.save(projection);
  }

  private void seedClosedStageDurationAt(
      OrderStatus stage, Instant exitedAt, long durationSeconds) {
    Order order = seedOrder();
    Instant enteredAt = exitedAt.minusSeconds(durationSeconds);
    OrderStageDuration stageDuration = new OrderStageDuration(order.getOrderId(), stage, enteredAt);
    stageDuration.close(exitedAt);
    stageDurationRepository.save(stageDuration);
  }
}
