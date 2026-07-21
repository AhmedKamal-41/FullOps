package com.ahmedali.fulfillops.order.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis is a disposable read cache for expensive KPI aggregate reads (overview/time-series/stage-
 * duration percentiles), never the system of record — every call falls back to computing directly
 * from PostgreSQL on any Redis error, the same shape as inventory-service's
 * InventoryAvailabilityCache. TTL-only invalidation (no explicit eviction) is deliberate: unlike
 * inventory availability, KPI reads are dashboards tolerant of a few seconds' staleness, not
 * correctness-critical for any write path, so there's nothing that needs to actively invalidate
 * them. Cache keys must include every filter the query used (see callers) so two different filter
 * combinations never collide on one key.
 */
@Component
public class KpiCache {

  private static final Logger log = LoggerFactory.getLogger(KpiCache.class);
  private static final String KEY_PREFIX = "ops:kpi:";

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final Counter cacheFailureCounter;
  private final Duration ttl;

  public KpiCache(
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      @Value("${app.ops.cache.ttl-seconds}") long ttlSeconds) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
    this.cacheFailureCounter = Counter.builder("ops.kpi.cache.failures").register(meterRegistry);
    this.ttl = Duration.ofSeconds(ttlSeconds);
  }

  public <T> Optional<T> get(String key, Class<T> type) {
    try {
      String json = redisTemplate.opsForValue().get(KEY_PREFIX + key);
      if (json == null) {
        return Optional.empty();
      }
      return Optional.of(objectMapper.readValue(json, type));
    } catch (RuntimeException e) {
      recordFailure("get", e);
      return Optional.empty();
    }
  }

  public void put(String key, Object value) {
    try {
      redisTemplate
          .opsForValue()
          .set(KEY_PREFIX + key, objectMapper.writeValueAsString(value), ttl);
    } catch (RuntimeException e) {
      recordFailure("put", e);
    }
  }

  private void recordFailure(String operation, RuntimeException e) {
    log.warn(
        "Redis {} failed for the KPI cache, falling back to PostgreSQL: {}",
        operation,
        e.getMessage());
    cacheFailureCounter.increment();
  }
}
