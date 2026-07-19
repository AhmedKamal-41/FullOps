package com.ahmedali.fulfillops.order.domain;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, UUID> {

  Page<Order> findByCustomerId(UUID customerId, Pageable pageable);
}
