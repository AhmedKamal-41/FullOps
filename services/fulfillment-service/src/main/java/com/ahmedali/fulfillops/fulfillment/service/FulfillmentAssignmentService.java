package com.ahmedali.fulfillops.fulfillment.service;

import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates fulfillment creation for an authorized payment. The orderId lookup below is
 * defense-in-depth on top of PaymentAuthorizedListener's own inbox dedup (fulfillments.order_id is
 * also UNIQUE at the database level) — it's what keeps "create exactly one fulfillment per paid
 * order" true even if this method were ever called twice for the same order.
 */
@Service
public class FulfillmentAssignmentService {

  private static final Logger log = LoggerFactory.getLogger(FulfillmentAssignmentService.class);

  private final FulfillmentRepository fulfillmentRepository;
  private final FulfillmentAssignmentTransaction assignmentTransaction;

  public FulfillmentAssignmentService(
      FulfillmentRepository fulfillmentRepository,
      FulfillmentAssignmentTransaction assignmentTransaction) {
    this.fulfillmentRepository = fulfillmentRepository;
    this.assignmentTransaction = assignmentTransaction;
  }

  public void assign(UUID orderId, UUID correlationId, UUID causationId) {
    if (fulfillmentRepository.findByOrderId(orderId).isPresent()) {
      log.info("fulfillment already exists for order {}, skipping", orderId);
      return;
    }
    assignmentTransaction.assign(orderId, correlationId, causationId);
  }
}
