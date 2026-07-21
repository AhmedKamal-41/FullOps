package com.ahmedali.fulfillops.order.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderCancellationRepository extends JpaRepository<OrderCancellation, UUID> {

  List<OrderCancellation> findByResolvedAtIsNullAndRequestedAtBefore(Instant cutoff);
}
