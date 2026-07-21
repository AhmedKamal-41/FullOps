package com.ahmedali.fulfillops.order.domain;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Dynamic, combinable filters for the work queue — any subset, any combination, still one real
 * indexed query with pagination, not a fetch-everything-then-filter-in-Java approach that would
 * break Page counts.
 */
public final class WorkQueueSpecifications {

  private WorkQueueSpecifications() {}

  public static Specification<OrderOperationsProjection> hasStatus(OrderStatus status) {
    return (root, query, cb) -> cb.equal(root.get("status"), status);
  }

  public static Specification<OrderOperationsProjection> hasCustomerId(UUID customerId) {
    return (root, query, cb) -> cb.equal(root.get("customerId"), customerId);
  }

  public static Specification<OrderOperationsProjection> isStuck(
      List<OrderStatus> openStatuses, Instant cutoff) {
    return (root, query, cb) ->
        cb.and(
            root.get("status").in(openStatuses),
            cb.lessThan(root.get("currentStageEnteredAt"), cutoff));
  }

  /** cutoffByStage maps a stage to "entered before this instant counts as breached." */
  public static Specification<OrderOperationsProjection> isSlaBreached(
      Map<OrderStatus, Instant> cutoffByStage) {
    return (root, query, cb) -> {
      Predicate[] perStage =
          cutoffByStage.entrySet().stream()
              .map(
                  entry ->
                      cb.and(
                          cb.equal(root.get("status"), entry.getKey()),
                          cb.lessThan(root.get("currentStageEnteredAt"), entry.getValue())))
              .toArray(Predicate[]::new);
      return cb.or(perStage);
    };
  }
}
