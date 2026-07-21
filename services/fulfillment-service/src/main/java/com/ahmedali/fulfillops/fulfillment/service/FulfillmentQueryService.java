package com.ahmedali.fulfillops.fulfillment.service;

import com.ahmedali.fulfillops.fulfillment.domain.Fulfillment;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentRepository;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentStatus;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentStatusHistory;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentStatusHistoryRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/** Read-only queries for operators: work lists filtered by status, one fulfillment, its history. */
@Service
public class FulfillmentQueryService {

  private final FulfillmentRepository fulfillmentRepository;
  private final FulfillmentStatusHistoryRepository statusHistoryRepository;

  public FulfillmentQueryService(
      FulfillmentRepository fulfillmentRepository,
      FulfillmentStatusHistoryRepository statusHistoryRepository) {
    this.fulfillmentRepository = fulfillmentRepository;
    this.statusHistoryRepository = statusHistoryRepository;
  }

  public Page<Fulfillment> list(FulfillmentStatus status, Pageable pageable) {
    if (status == null) {
      return fulfillmentRepository.findAll(pageable);
    }
    return fulfillmentRepository.findByStatus(status, pageable);
  }

  public Optional<Fulfillment> find(UUID fulfillmentId) {
    return fulfillmentRepository.findById(fulfillmentId);
  }

  public List<FulfillmentStatusHistory> history(UUID fulfillmentId) {
    return statusHistoryRepository.findByFulfillmentIdOrderByOccurredAtAsc(fulfillmentId);
  }
}
