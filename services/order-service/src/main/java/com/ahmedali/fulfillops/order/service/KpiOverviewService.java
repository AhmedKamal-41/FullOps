package com.ahmedali.fulfillops.order.service;

import com.ahmedali.fulfillops.order.cache.KpiCache;
import com.ahmedali.fulfillops.order.domain.OperationsIncidentRepository;
import com.ahmedali.fulfillops.order.domain.OrderOperationsProjectionRepository;
import com.ahmedali.fulfillops.order.domain.OrderStageDurationRepository;
import com.ahmedali.fulfillops.order.domain.OrderStatus;
import com.ahmedali.fulfillops.order.messaging.DeadLetterEventRepository;
import com.ahmedali.fulfillops.order.messaging.DeadLetterEventStatus;
import com.ahmedali.fulfillops.order.messaging.OutboxEventRepository;
import com.ahmedali.fulfillops.order.messaging.OutboxState;
import com.ahmedali.fulfillops.order.web.dto.KpiOverviewResponse;
import com.ahmedali.fulfillops.order.web.dto.ReasonCodeCountResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Backs GET /api/v1/ops/kpis/overview. Every formula is documented in docs/KPI_DICTIONARY.md — this
 * class is that dictionary's implementation, not a second, possibly-drifting definition of the same
 * numbers. Cached (see KpiCache) since a request touches several COUNT/GROUP BY queries across
 * projection, incident, and messaging tables.
 */
@Service
public class KpiOverviewService {

  private final OrderOperationsProjectionRepository projectionRepository;
  private final OrderStageDurationRepository stageDurationRepository;
  private final OperationsIncidentRepository incidentRepository;
  private final DeadLetterEventRepository deadLetterEventRepository;
  private final OutboxEventRepository outboxEventRepository;
  private final KpiCache cache;

  public KpiOverviewService(
      OrderOperationsProjectionRepository projectionRepository,
      OrderStageDurationRepository stageDurationRepository,
      OperationsIncidentRepository incidentRepository,
      DeadLetterEventRepository deadLetterEventRepository,
      OutboxEventRepository outboxEventRepository,
      KpiCache cache) {
    this.projectionRepository = projectionRepository;
    this.stageDurationRepository = stageDurationRepository;
    this.incidentRepository = incidentRepository;
    this.deadLetterEventRepository = deadLetterEventRepository;
    this.outboxEventRepository = outboxEventRepository;
    this.cache = cache;
  }

  public KpiOverviewResponse overview(Instant from, Instant to) {
    String cacheKey = "overview:" + from + ":" + to;
    return cache
        .get(cacheKey, KpiOverviewResponse.class)
        .orElseGet(
            () -> {
              KpiOverviewResponse computed = compute(from, to);
              cache.put(cacheKey, computed);
              return computed;
            });
  }

  private KpiOverviewResponse compute(Instant from, Instant to) {
    long ordersReceived = projectionRepository.countByCreatedAtBetween(from, to);
    long ordersCompleted =
        projectionRepository.countByStatusAndCurrentStageEnteredAtBetween(
            OrderStatus.DELIVERED, from, to);
    long ordersCancelled =
        projectionRepository.countByStatusAndCurrentStageEnteredAtBetween(
            OrderStatus.CANCELLED, from, to);

    long inventoryRejections =
        projectionRepository.countByCreatedAtBetweenAndInventoryRejectionReasonCodeIsNotNull(
            from, to);
    List<ReasonCodeCountResponse> inventoryRejectionReasons =
        projectionRepository.countInventoryRejectionsByReason(from, to).stream()
            .map(row -> new ReasonCodeCountResponse(row.getReasonCode(), row.getOrderCount()))
            .toList();

    long paymentEligibleOrders = projectionRepository.countPaymentEligibleOrders(from, to);
    long paymentDeclines =
        projectionRepository.countByCreatedAtBetweenAndPaymentDeclineReasonCodeIsNotNull(from, to);
    List<ReasonCodeCountResponse> paymentDeclineReasons =
        projectionRepository.countPaymentDeclinesByReason(from, to).stream()
            .map(row -> new ReasonCodeCountResponse(row.getReasonCode(), row.getOrderCount()))
            .toList();
    long paymentTechnicalFailureOrders =
        projectionRepository.countByCreatedAtBetweenAndPaymentTechnicalFailureCountGreaterThan(
            from, to, 0);

    long fulfillmentThroughput =
        stageDurationRepository.countByStageAndEnteredAtBetween(OrderStatus.DISPATCHED, from, to);

    long dltBacklogCount =
        deadLetterEventRepository.countByStatus(DeadLetterEventStatus.PENDING_REVIEW);
    Instant oldestDltEventAt =
        deadLetterEventRepository
            .findFirstByStatusOrderByCreatedAtAsc(DeadLetterEventStatus.PENDING_REVIEW)
            .map(event -> event.getCreatedAt())
            .orElse(null);
    long outboxBacklogCount = outboxEventRepository.countByStateNot(OutboxState.PUBLISHED.name());
    Instant oldestOutboxEventAt =
        outboxEventRepository
            .findFirstByStateNotOrderByCreatedAtAsc(OutboxState.PUBLISHED.name())
            .map(event -> event.getOccurredAt())
            .orElse(null);

    long cancellationStuckIncidents = incidentRepository.countCancellationStuckIncidents(from, to);
    long cancellationStuckRecovered =
        incidentRepository.countCancellationStuckIncidentsThatRecovered(from, to);
    long ordersWithAnyIncident = incidentRepository.countOrdersWithAnyIncident(from, to);

    return new KpiOverviewResponse(
        from,
        to,
        ordersReceived,
        ordersCompleted,
        ordersCancelled,
        inventoryRejections,
        rate(inventoryRejections, ordersReceived),
        inventoryRejectionReasons,
        paymentDeclines,
        rate(paymentDeclines, paymentEligibleOrders),
        paymentDeclineReasons,
        rate(paymentTechnicalFailureOrders, paymentEligibleOrders),
        fulfillmentThroughput,
        dltBacklogCount,
        oldestDltEventAt,
        outboxBacklogCount,
        oldestOutboxEventAt,
        rate(cancellationStuckRecovered, cancellationStuckIncidents),
        rate(ordersWithAnyIncident, ordersReceived));
  }

  private static double rate(long numerator, long denominator) {
    return denominator == 0 ? 0.0 : (double) numerator / denominator;
  }
}
