package com.ahmedali.fulfillops.order.service;

import com.ahmedali.fulfillops.order.domain.OrderOperationsProjection;
import com.ahmedali.fulfillops.order.domain.OrderOperationsProjectionRepository;
import com.ahmedali.fulfillops.order.domain.OrderStatus;
import com.ahmedali.fulfillops.order.domain.WorkQueueSpecifications;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

/**
 * The exception work queue: every open order, searchable/filterable by status, customer, SLA
 * breach, and stuck — a real indexed query via JpaSpecificationExecutor for any combination of
 * filters, not a fetch-then-filter-in-Java approach that would break Page counts. Uncached (see
 * StageDurationKpiService's Javadoc for why backlog-shaped reads stay uncached).
 */
@Service
public class WorkQueueService {

  private final OrderOperationsProjectionRepository repository;
  private final OpsSlaProperties slaProperties;
  private final Duration stuckThreshold;

  public WorkQueueService(
      OrderOperationsProjectionRepository repository,
      OpsSlaProperties slaProperties,
      @Value("${app.reconciliation.stuck-threshold}") Duration stuckThreshold) {
    this.repository = repository;
    this.slaProperties = slaProperties;
    this.stuckThreshold = stuckThreshold;
  }

  public Page<OrderOperationsProjection> search(WorkQueueFilter filter, Pageable pageable) {
    return repository.findAll(buildSpecification(filter), pageable);
  }

  public List<OrderOperationsProjection> searchAll(WorkQueueFilter filter, Sort sort) {
    return repository.findAll(buildSpecification(filter), sort);
  }

  private Specification<OrderOperationsProjection> buildSpecification(WorkQueueFilter filter) {
    Specification<OrderOperationsProjection> spec = Specification.allOf();
    if (filter.status() != null) {
      spec = spec.and(WorkQueueSpecifications.hasStatus(filter.status()));
    }
    if (filter.customerId() != null) {
      spec = spec.and(WorkQueueSpecifications.hasCustomerId(filter.customerId()));
    }
    if (Boolean.TRUE.equals(filter.stuck())) {
      spec =
          spec.and(
              WorkQueueSpecifications.isStuck(
                  OrderStatus.OPEN_STAGES, Instant.now().minus(stuckThreshold)));
    }
    if (Boolean.TRUE.equals(filter.slaBreached())) {
      spec = spec.and(WorkQueueSpecifications.isSlaBreached(cutoffByStage()));
    }
    return spec;
  }

  private Map<OrderStatus, Instant> cutoffByStage() {
    Instant now = Instant.now();
    Map<OrderStatus, Instant> cutoffs = new EnumMap<>(OrderStatus.class);
    slaProperties
        .getStageThresholds()
        .forEach(
            (stage, threshold) -> cutoffs.put(OrderStatus.valueOf(stage), now.minus(threshold)));
    return cutoffs;
  }
}
