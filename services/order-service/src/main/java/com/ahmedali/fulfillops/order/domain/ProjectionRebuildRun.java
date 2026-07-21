package com.ahmedali.fulfillops.order.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Metadata about one run of OperationsProjectionRebuildService — named for what it actually tracks:
 * rebuild recomputes order_operations_projection/order_stage_duration from this service's own
 * durable tables (orders, order_status_history, order_cancellation, operations_incident), not from
 * a Kafka consumer offset, so there's no "checkpoint" to resume from, only a transactional
 * truncate-and-repopulate.
 */
@Entity
@Table(name = "projection_rebuild_run")
public class ProjectionRebuildRun {

  @Id private UUID rebuildId;

  private Instant startedAt;
  private Instant completedAt;

  @Enumerated(EnumType.STRING)
  private ProjectionRebuildStatus status;

  private String triggeredBy;
  private Integer ordersProcessed;
  private String failureDetail;

  protected ProjectionRebuildRun() {
    // JPA
  }

  public ProjectionRebuildRun(String triggeredBy) {
    this.rebuildId = UUID.randomUUID();
    this.startedAt = Instant.now();
    this.status = ProjectionRebuildStatus.RUNNING;
    this.triggeredBy = triggeredBy;
  }

  public void complete(int ordersProcessed) {
    this.status = ProjectionRebuildStatus.COMPLETED;
    this.completedAt = Instant.now();
    this.ordersProcessed = ordersProcessed;
  }

  public void fail(String failureDetail) {
    this.status = ProjectionRebuildStatus.FAILED;
    this.completedAt = Instant.now();
    this.failureDetail = failureDetail;
  }

  public UUID getRebuildId() {
    return rebuildId;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public ProjectionRebuildStatus getStatus() {
    return status;
  }

  public String getTriggeredBy() {
    return triggeredBy;
  }

  public Integer getOrdersProcessed() {
    return ordersProcessed;
  }

  public String getFailureDetail() {
    return failureDetail;
  }
}
