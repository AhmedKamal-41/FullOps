package com.ahmedali.fulfillops.fulfillment.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FulfillmentRepository extends JpaRepository<Fulfillment, UUID> {

  Optional<Fulfillment> findByOrderId(UUID orderId);

  Page<Fulfillment> findByStatus(FulfillmentStatus status, Pageable pageable);
}
