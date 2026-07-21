package com.ahmedali.fulfillops.order.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, UUID> {

  List<OrderStatusHistory> findByOrderIdOrderByOccurredAt(UUID orderId);
}
