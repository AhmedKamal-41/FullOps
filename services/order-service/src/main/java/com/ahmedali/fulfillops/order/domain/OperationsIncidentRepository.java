package com.ahmedali.fulfillops.order.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * JpaSpecificationExecutor backs GET /api/v1/ops/incidents's combinable optional filters
 * (status/kind/orderId) — see IncidentSpecifications.
 */
public interface OperationsIncidentRepository
    extends JpaRepository<OperationsIncident, UUID>, JpaSpecificationExecutor<OperationsIncident> {

  Optional<OperationsIncident> findByOrderIdAndKindAndStatus(
      UUID orderId, IncidentKind kind, IncidentStatus status);

  List<OperationsIncident> findByOrderId(UUID orderId);

  // The dedup check used to be "any OPEN incident of this kind" — under the three-state
  // OPEN/ACKNOWLEDGED/RESOLVED model, an acknowledged-but-unresolved incident must still block a
  // duplicate (see V4__operations.sql's matching partial unique index), so this checks "not yet
  // RESOLVED" instead of strictly "OPEN".
  Optional<OperationsIncident> findByOrderIdAndKindAndStatusNot(
      UUID orderId, IncidentKind kind, IncidentStatus status);

  int countByOrderIdAndStatusNot(UUID orderId, IncidentStatus status);

  // "Recovery success rate" (Recovery success/manual-touch rate KPI, docs/KPI_DICTIONARY.md):
  // ReconciliationService never resolves a CANCELLATION_STUCK incident itself either way — the
  // order's own final status (CANCELLED vs REQUIRES_REVIEW) is what actually shows whether the
  // one automatic retry worked. A native query is used because JPQL has no way to join
  // OperationsIncident to OrderOperationsProjection — they share a plain orderId column, not a
  // mapped relationship (by design: no service, and no entity within one, implies FK ownership
  // across these two on purpose).
  @Query(
      value =
          """
          SELECT COUNT(DISTINCT i.order_id) FROM operations_incident i
          WHERE i.kind = 'CANCELLATION_STUCK' AND i.created_at BETWEEN :from AND :to
          """,
      nativeQuery = true)
  long countCancellationStuckIncidents(@Param("from") Instant from, @Param("to") Instant to);

  @Query(
      value =
          """
          SELECT COUNT(DISTINCT i.order_id) FROM operations_incident i
          JOIN order_operations_projection p ON p.order_id = i.order_id
          WHERE i.kind = 'CANCELLATION_STUCK' AND i.created_at BETWEEN :from AND :to
                AND p.status = 'CANCELLED'
          """,
      nativeQuery = true)
  long countCancellationStuckIncidentsThatRecovered(
      @Param("from") Instant from, @Param("to") Instant to);

  // "Manual-touch rate": what fraction of orders placed in the window ever needed an incident at
  // all (of any kind, not just CANCELLATION_STUCK).
  @Query(
      value =
          """
          SELECT COUNT(DISTINCT i.order_id) FROM operations_incident i
          JOIN orders o ON o.order_id = i.order_id
          WHERE o.created_at BETWEEN :from AND :to
          """,
      nativeQuery = true)
  long countOrdersWithAnyIncident(@Param("from") Instant from, @Param("to") Instant to);
}
