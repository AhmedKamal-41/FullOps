package com.ahmedali.fulfillops.order.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * JpaSpecificationExecutor backs the work queue's combinatorial optional filters (status, customer,
 * SLA-breached, stuck — any subset, any combination) — see WorkQueueSpecifications, the established
 * Spring Data pattern for this instead of one derived-query method per filter combination.
 */
public interface OrderOperationsProjectionRepository
    extends JpaRepository<OrderOperationsProjection, UUID>,
        JpaSpecificationExecutor<OrderOperationsProjection> {

  List<OrderOperationsProjection> findByStatusIn(List<OrderStatus> statuses);

  List<OrderOperationsProjection> findByStatusInAndCurrentStageEnteredAtBefore(
      List<OrderStatus> statuses, Instant cutoff);

  @Query(
      """
      SELECT p.status AS status, COUNT(p) AS orderCount
      FROM OrderOperationsProjection p
      WHERE p.status IN :statuses
      GROUP BY p.status
      """)
  List<StageCount> countByStatusGroupedByStatus(@Param("statuses") List<OrderStatus> statuses);

  long countByStatusInAndCreatedAtBetween(List<OrderStatus> statuses, Instant from, Instant to);

  long countByStatusAndCurrentStageEnteredAtBetween(OrderStatus status, Instant from, Instant to);

  long countByCreatedAtBetween(Instant from, Instant to);

  long countByCreatedAtBetweenAndInventoryRejectionReasonCodeIsNotNull(Instant from, Instant to);

  @Query(
      """
      SELECT p.inventoryRejectionReasonCode AS reasonCode, COUNT(p) AS orderCount
      FROM OrderOperationsProjection p
      WHERE p.createdAt BETWEEN :from AND :to AND p.inventoryRejectionReasonCode IS NOT NULL
      GROUP BY p.inventoryRejectionReasonCode
      """)
  List<ReasonCodeCount> countInventoryRejectionsByReason(
      @Param("from") Instant from, @Param("to") Instant to);

  long countByCreatedAtBetweenAndPaymentDeclineReasonCodeIsNotNull(Instant from, Instant to);

  @Query(
      """
      SELECT p.paymentDeclineReasonCode AS reasonCode, COUNT(p) AS orderCount
      FROM OrderOperationsProjection p
      WHERE p.createdAt BETWEEN :from AND :to AND p.paymentDeclineReasonCode IS NOT NULL
      GROUP BY p.paymentDeclineReasonCode
      """)
  List<ReasonCodeCount> countPaymentDeclinesByReason(
      @Param("from") Instant from, @Param("to") Instant to);

  long countByCreatedAtBetweenAndPaymentTechnicalFailureCountGreaterThan(
      Instant from, Instant to, int floor);

  // "Eligible for payment" means the order got at least as far as INVENTORY_RESERVED — payment
  // is never attempted before that, so counting declines/technical-failures against every order
  // ever placed (including ones inventory rejected outright) would understate the real rate.
  @Query(
      """
      SELECT COUNT(p) FROM OrderOperationsProjection p
      WHERE p.createdAt BETWEEN :from AND :to AND p.inventoryRejectionReasonCode IS NULL
      """)
  long countPaymentEligibleOrders(@Param("from") Instant from, @Param("to") Instant to);

  // interval is always "day" or "hour" — validated against TimeSeriesInterval before it ever
  // reaches this query, never a raw client-supplied string, so binding it as a parameter (rather
  // than a literal) to Postgres's date_trunc is safe.
  @Query(
      value =
          """
          SELECT date_trunc(:interval, created_at) AS bucketStart, COUNT(*) AS orderCount
          FROM order_operations_projection
          WHERE created_at BETWEEN :from AND :to
          GROUP BY 1 ORDER BY 1
          """,
      nativeQuery = true)
  List<BucketCount> countReceivedByBucket(
      @Param("interval") String interval, @Param("from") Instant from, @Param("to") Instant to);

  @Query(
      value =
          """
          SELECT date_trunc(:interval, current_stage_entered_at) AS bucketStart,
                 COUNT(*) AS orderCount
          FROM order_operations_projection
          WHERE status = :status AND current_stage_entered_at BETWEEN :from AND :to
          GROUP BY 1 ORDER BY 1
          """,
      nativeQuery = true)
  List<BucketCount> countByStatusGroupedByBucket(
      @Param("status") String status,
      @Param("interval") String interval,
      @Param("from") Instant from,
      @Param("to") Instant to);

  interface StageCount {
    OrderStatus getStatus();

    long getOrderCount();
  }

  interface ReasonCodeCount {
    String getReasonCode();

    long getOrderCount();
  }

  interface BucketCount {
    Instant getBucketStart();

    long getOrderCount();
  }
}
