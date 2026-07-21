package com.ahmedali.fulfillops.fulfillment.service;

import com.ahmedali.fulfillops.fulfillment.domain.Fulfillment;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentRepository;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentStatus;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentStatusHistory;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentStatusHistoryRepository;
import com.ahmedali.fulfillops.fulfillment.domain.WarehouseAssigner;
import com.ahmedali.fulfillops.fulfillment.messaging.OutboxEventWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a new fulfillment, its initial ASSIGNED history row, and the FulfillmentAssigned.v1
 * outbox event together, atomically — either all three commit or none does. Called at most once per
 * order by FulfillmentAssignmentService.
 */
@Component
public class FulfillmentAssignmentTransaction {

  private static final String ASSIGNED_ACTOR = "system";
  private static final String ASSIGNED_EVENT_TYPE = "FulfillmentAssigned";
  private static final int EVENT_VERSION = 1;

  private final FulfillmentRepository fulfillmentRepository;
  private final FulfillmentStatusHistoryRepository statusHistoryRepository;
  private final WarehouseAssigner warehouseAssigner;
  private final OutboxEventWriter outboxEventWriter;
  private final Duration slaDuration;

  public FulfillmentAssignmentTransaction(
      FulfillmentRepository fulfillmentRepository,
      FulfillmentStatusHistoryRepository statusHistoryRepository,
      WarehouseAssigner warehouseAssigner,
      OutboxEventWriter outboxEventWriter,
      @Value("${app.fulfillment.sla-duration}") Duration slaDuration) {
    this.fulfillmentRepository = fulfillmentRepository;
    this.statusHistoryRepository = statusHistoryRepository;
    this.warehouseAssigner = warehouseAssigner;
    this.outboxEventWriter = outboxEventWriter;
    this.slaDuration = slaDuration;
  }

  @Transactional
  public Fulfillment assign(UUID orderId, UUID correlationId, UUID causationId) {
    UUID fulfillmentId = UUID.randomUUID();
    String warehouseId = warehouseAssigner.assign(orderId);
    Instant slaDueAt = Instant.now().plus(slaDuration);

    Fulfillment fulfillment =
        Fulfillment.create(fulfillmentId, orderId, warehouseId, slaDueAt, correlationId);
    fulfillmentRepository.save(fulfillment);

    statusHistoryRepository.save(
        new FulfillmentStatusHistory(
            fulfillmentId, FulfillmentStatus.ASSIGNED, ASSIGNED_ACTOR, null));

    outboxEventWriter.write(
        ASSIGNED_EVENT_TYPE,
        EVENT_VERSION,
        orderId,
        correlationId,
        causationId,
        new AssignedPayload(fulfillmentId));

    return fulfillment;
  }

  private record AssignedPayload(UUID fulfillmentId) {}
}
