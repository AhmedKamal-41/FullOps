package com.ahmedali.fulfillops.order.service;

import com.ahmedali.fulfillops.order.cache.KpiCache;
import com.ahmedali.fulfillops.order.domain.OrderOperationsProjection;
import com.ahmedali.fulfillops.order.domain.OrderOperationsProjectionRepository;
import com.ahmedali.fulfillops.order.domain.OrderStageDurationRepository;
import com.ahmedali.fulfillops.order.domain.OrderStageDurationRepository.PercentileRow;
import com.ahmedali.fulfillops.order.domain.OrderStatus;
import com.ahmedali.fulfillops.order.web.dto.AgeBucketResponse;
import com.ahmedali.fulfillops.order.web.dto.BacklogResponse;
import com.ahmedali.fulfillops.order.web.dto.CycleTimePercentilesResponse;
import com.ahmedali.fulfillops.order.web.dto.StageBacklogResponse;
import com.ahmedali.fulfillops.order.web.dto.StageDurationKpiResponse;
import com.ahmedali.fulfillops.order.web.dto.StagePercentilesResponse;
import com.ahmedali.fulfillops.order.web.dto.StuckOrdersResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Backs GET /api/v1/ops/kpis/stage-durations, /api/v1/ops/backlog, and
 * /api/v1/ops/kpis/stuck-orders. Backlog and stuck-orders are real-time snapshots (uncached — a
 * single indexed query each, and operators need them fresh); stage-duration percentiles are cached
 * like the rest of KpiOverviewService/KpiTimeSeriesService's expensive aggregate reads.
 */
@Service
public class StageDurationKpiService {

  private final OrderStageDurationRepository stageDurationRepository;
  private final OrderOperationsProjectionRepository projectionRepository;
  private final OpsSlaProperties slaProperties;
  private final KpiCache cache;
  private final Duration stuckThreshold;

  public StageDurationKpiService(
      OrderStageDurationRepository stageDurationRepository,
      OrderOperationsProjectionRepository projectionRepository,
      OpsSlaProperties slaProperties,
      KpiCache cache,
      @Value("${app.reconciliation.stuck-threshold}") Duration stuckThreshold) {
    this.stageDurationRepository = stageDurationRepository;
    this.projectionRepository = projectionRepository;
    this.slaProperties = slaProperties;
    this.cache = cache;
    this.stuckThreshold = stuckThreshold;
  }

  public StageDurationKpiResponse stageDurations(Instant from, Instant to) {
    String cacheKey = "stage-durations:" + from + ":" + to;
    return cache
        .get(cacheKey, StageDurationKpiResponse.class)
        .orElseGet(
            () -> {
              StageDurationKpiResponse computed = computeStageDurations(from, to);
              cache.put(cacheKey, computed);
              return computed;
            });
  }

  private StageDurationKpiResponse computeStageDurations(Instant from, Instant to) {
    List<StagePercentilesResponse> stages = new ArrayList<>();
    for (OrderStatus stage : OrderStatus.values()) {
      PercentileRow row = stageDurationRepository.percentilesForStage(stage.name(), from, to);
      stages.add(
          new StagePercentilesResponse(
              stage.name(), row.getP50(), row.getP90(), row.getP99(), row.getSampleCount()));
    }
    PercentileRow cycleTime = stageDurationRepository.endToEndCycleTimePercentiles(from, to);
    CycleTimePercentilesResponse endToEnd =
        new CycleTimePercentilesResponse(
            cycleTime.getP50(), cycleTime.getP90(), cycleTime.getP99(), cycleTime.getSampleCount());
    return new StageDurationKpiResponse(from, to, stages, endToEnd);
  }

  public BacklogResponse backlog() {
    Instant now = Instant.now();
    List<OrderOperationsProjection> openOrders =
        projectionRepository.findByStatusIn(OrderStatus.OPEN_STAGES);
    List<StageBacklogResponse> stages =
        OrderStatus.OPEN_STAGES.stream()
            .map(stage -> backlogForStage(stage, openOrders, now))
            .toList();
    return new BacklogResponse(stages);
  }

  private StageBacklogResponse backlogForStage(
      OrderStatus stage, List<OrderOperationsProjection> openOrders, Instant now) {
    List<OrderOperationsProjection> inStage =
        openOrders.stream().filter(order -> order.getStatus() == stage).toList();
    Duration threshold = slaProperties.thresholdFor(stage);
    long breached =
        threshold == null
            ? 0
            : inStage.stream().filter(order -> ageOf(order, now).compareTo(threshold) > 0).count();
    double breachRate = inStage.isEmpty() ? 0.0 : (double) breached / inStage.size();
    return new StageBacklogResponse(stage.name(), inStage.size(), breached, breachRate);
  }

  public StuckOrdersResponse stuckOrders() {
    Instant now = Instant.now();
    List<OrderOperationsProjection> stuckOrders =
        projectionRepository.findByStatusIn(OrderStatus.OPEN_STAGES).stream()
            .filter(order -> ageOf(order, now).compareTo(stuckThreshold) > 0)
            .toList();

    List<Duration> boundaries = slaProperties.getAgeBuckets();
    List<AgeBucketResponse> buckets = new ArrayList<>();
    for (int i = 0; i < boundaries.size(); i++) {
      Duration lower = boundaries.get(i);
      Duration upper = i + 1 < boundaries.size() ? boundaries.get(i + 1) : null;
      long count =
          stuckOrders.stream().filter(order -> inBucket(ageOf(order, now), lower, upper)).count();
      buckets.add(new AgeBucketResponse(bucketLabel(lower, upper), count));
    }
    return new StuckOrdersResponse(stuckOrders.size(), buckets);
  }

  private static Duration ageOf(OrderOperationsProjection order, Instant now) {
    return Duration.between(order.getCurrentStageEnteredAt(), now);
  }

  private static boolean inBucket(Duration age, Duration lower, Duration upper) {
    return age.compareTo(lower) >= 0 && (upper == null || age.compareTo(upper) < 0);
  }

  private static String bucketLabel(Duration lower, Duration upper) {
    return upper == null ? lower.toHours() + "h+" : lower.toHours() + "h-" + upper.toHours() + "h";
  }
}
