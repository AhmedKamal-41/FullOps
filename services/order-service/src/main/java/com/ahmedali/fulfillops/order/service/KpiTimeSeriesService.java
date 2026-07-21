package com.ahmedali.fulfillops.order.service;

import com.ahmedali.fulfillops.order.cache.KpiCache;
import com.ahmedali.fulfillops.order.domain.OrderOperationsProjectionRepository;
import com.ahmedali.fulfillops.order.domain.OrderOperationsProjectionRepository.BucketCount;
import com.ahmedali.fulfillops.order.domain.OrderStatus;
import com.ahmedali.fulfillops.order.domain.TimeSeriesInterval;
import com.ahmedali.fulfillops.order.web.dto.KpiTimeSeriesResponse;
import com.ahmedali.fulfillops.order.web.dto.TimeSeriesPointResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/** Backs GET /api/v1/ops/kpis/timeseries. See docs/KPI_DICTIONARY.md for the bucketing rules. */
@Service
public class KpiTimeSeriesService {

  private final OrderOperationsProjectionRepository projectionRepository;
  private final KpiCache cache;

  public KpiTimeSeriesService(
      OrderOperationsProjectionRepository projectionRepository, KpiCache cache) {
    this.projectionRepository = projectionRepository;
    this.cache = cache;
  }

  public KpiTimeSeriesResponse timeSeries(Instant from, Instant to, TimeSeriesInterval interval) {
    String cacheKey = "timeseries:" + interval + ":" + from + ":" + to;
    return cache
        .get(cacheKey, KpiTimeSeriesResponse.class)
        .orElseGet(
            () -> {
              KpiTimeSeriesResponse computed = compute(from, to, interval);
              cache.put(cacheKey, computed);
              return computed;
            });
  }

  private KpiTimeSeriesResponse compute(Instant from, Instant to, TimeSeriesInterval interval) {
    String field = interval.toDateTruncField();
    Map<Instant, Long> received =
        toMap(projectionRepository.countReceivedByBucket(field, from, to));
    Map<Instant, Long> completed =
        toMap(
            projectionRepository.countByStatusGroupedByBucket(
                OrderStatus.DELIVERED.name(), field, from, to));
    Map<Instant, Long> cancelled =
        toMap(
            projectionRepository.countByStatusGroupedByBucket(
                OrderStatus.CANCELLED.name(), field, from, to));

    // A TreeMap over every bucket any of the three counts touched — a bucket with zero of one
    // kind (e.g. no cancellations that day) still needs a point, not a missing gap.
    TreeMap<Instant, Long> allBuckets = new TreeMap<>();
    allBuckets.putAll(received);
    allBuckets.putAll(completed);
    allBuckets.putAll(cancelled);

    List<TimeSeriesPointResponse> points =
        allBuckets.keySet().stream()
            .map(
                bucket ->
                    new TimeSeriesPointResponse(
                        bucket,
                        received.getOrDefault(bucket, 0L),
                        completed.getOrDefault(bucket, 0L),
                        cancelled.getOrDefault(bucket, 0L)))
            .toList();

    return new KpiTimeSeriesResponse(from, to, interval.name(), points);
  }

  private static Map<Instant, Long> toMap(List<BucketCount> rows) {
    Map<Instant, Long> map = new TreeMap<>();
    for (BucketCount row : rows) {
      map.put(row.getBucketStart(), row.getOrderCount());
    }
    return map;
  }
}
