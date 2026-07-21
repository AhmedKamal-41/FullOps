package com.ahmedali.fulfillops.order.domain;

import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Dynamic, combinable filters for GET /api/v1/ops/incidents — status/kind/orderId, any subset, any
 * combination, still one real query with pagination. Mirrors WorkQueueSpecifications: the
 * IncidentController's previous manual if/else already covered every status×kind combination with
 * two filters; adding a third (orderId, for Order Detail's "this order's incidents" view) would
 * have doubled that branching again, which is exactly what dynamic Specifications avoid.
 */
public final class IncidentSpecifications {

  private IncidentSpecifications() {}

  public static Specification<OperationsIncident> hasStatus(IncidentStatus status) {
    return (root, query, cb) -> cb.equal(root.get("status"), status);
  }

  public static Specification<OperationsIncident> hasKind(IncidentKind kind) {
    return (root, query, cb) -> cb.equal(root.get("kind"), kind);
  }

  public static Specification<OperationsIncident> hasOrderId(UUID orderId) {
    return (root, query, cb) -> cb.equal(root.get("orderId"), orderId);
  }
}
