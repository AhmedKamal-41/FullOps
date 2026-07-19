package com.ahmedali.fulfillops.inventory.cache;

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
 * Redis is a disposable read cache for availability, never the system of record — PostgreSQL alone
 * decides whether a reservation succeeds (see ReservationTransaction). Every call here is wrapped
 * so a Redis outage degrades to a Postgres read on every request, not an error: callers always get
 * an Optional back, never a Redis exception.
 */
@Component
public class InventoryAvailabilityCache {

  private static final Logger log = LoggerFactory.getLogger(InventoryAvailabilityCache.class);
  private static final String KEY_PREFIX = "inventory:availability:";

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final Counter cacheFailureCounter;
  private final Duration ttl;

  public InventoryAvailabilityCache(
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      @Value("${app.inventory.cache.ttl-seconds}") long ttlSeconds) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
    this.cacheFailureCounter = Counter.builder("inventory.cache.failures").register(meterRegistry);
    this.ttl = Duration.ofSeconds(ttlSeconds);
  }

  public Optional<AvailabilitySnapshot> get(String sku) {
    try {
      String json = redisTemplate.opsForValue().get(key(sku));
      if (json == null) {
        return Optional.empty();
      }
      return Optional.of(objectMapper.readValue(json, AvailabilitySnapshot.class));
    } catch (RuntimeException e) {
      recordFailure("get", e);
      return Optional.empty();
    }
  }

  public void put(String sku, AvailabilitySnapshot snapshot) {
    try {
      redisTemplate.opsForValue().set(key(sku), objectMapper.writeValueAsString(snapshot), ttl);
    } catch (RuntimeException e) {
      recordFailure("put", e);
    }
  }

  public void evict(String sku) {
    try {
      redisTemplate.delete(key(sku));
    } catch (RuntimeException e) {
      recordFailure("evict", e);
    }
  }

  private void recordFailure(String operation, RuntimeException e) {
    log.warn(
        "Redis {} failed for inventory availability cache, falling back to PostgreSQL: {}",
        operation,
        e.getMessage());
    cacheFailureCounter.increment();
  }

  private static String key(String sku) {
    return KEY_PREFIX + sku;
  }
}
