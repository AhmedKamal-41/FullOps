package com.ahmedali.fulfillops.fulfillment.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FulfillmentStatusHistoryRepository
    extends JpaRepository<FulfillmentStatusHistory, UUID> {

  List<FulfillmentStatusHistory> findByFulfillmentIdOrderByOccurredAtAsc(UUID fulfillmentId);
}
