package com.ahmedali.fulfillops.order.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderStageDurationRepository extends JpaRepository<OrderStageDuration, UUID> {

  Optional<OrderStageDuration> findByOrderIdAndStage(UUID orderId, OrderStatus stage);

  // Fulfillment throughput: orders dispatched in the window (they entered the DISPATCHED stage),
  // regardless of whether they've reached DELIVERED yet.
  long countByStageAndEnteredAtBetween(OrderStatus stage, Instant from, Instant to);

  // Postgres's own percentile_cont — boring, correct, no hand-rolled percentile math. Only closed
  // (exited) occurrences within the window count; stage is bound by name since JPQL enums bind as
  // the enum, not the underlying VARCHAR percentile_cont needs to sort numerically over.
  @Query(
      value =
          """
          SELECT
              percentile_cont(0.5) WITHIN GROUP (ORDER BY duration_seconds) AS p50,
              percentile_cont(0.9) WITHIN GROUP (ORDER BY duration_seconds) AS p90,
              percentile_cont(0.99) WITHIN GROUP (ORDER BY duration_seconds) AS p99,
              COUNT(*) AS sampleCount
          FROM order_stage_duration
          WHERE stage = :stage AND exited_at BETWEEN :from AND :to
          """,
      nativeQuery = true)
  PercentileRow percentilesForStage(
      @Param("stage") String stage, @Param("from") Instant from, @Param("to") Instant to);

  // End-to-end cycle time is order placement to delivery. DELIVERED is terminal, so its
  // order_stage_duration row never closes (exited_at/duration_seconds stay null forever) — the
  // moment of delivery is that row's own entered_at, which this joins against orders.created_at.
  @Query(
      value =
          """
          SELECT
              percentile_cont(0.5) WITHIN GROUP (
                  ORDER BY EXTRACT(EPOCH FROM (osd.entered_at - o.created_at))) AS p50,
              percentile_cont(0.9) WITHIN GROUP (
                  ORDER BY EXTRACT(EPOCH FROM (osd.entered_at - o.created_at))) AS p90,
              percentile_cont(0.99) WITHIN GROUP (
                  ORDER BY EXTRACT(EPOCH FROM (osd.entered_at - o.created_at))) AS p99,
              COUNT(*) AS sampleCount
          FROM order_stage_duration osd
          JOIN orders o ON o.order_id = osd.order_id
          WHERE osd.stage = 'DELIVERED' AND osd.entered_at BETWEEN :from AND :to
          """,
      nativeQuery = true)
  PercentileRow endToEndCycleTimePercentiles(@Param("from") Instant from, @Param("to") Instant to);

  interface PercentileRow {
    Double getP50();

    Double getP90();

    Double getP99();

    long getSampleCount();
  }
}
