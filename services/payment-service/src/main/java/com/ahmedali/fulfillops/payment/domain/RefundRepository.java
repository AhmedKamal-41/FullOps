package com.ahmedali.fulfillops.payment.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

  Optional<Refund> findByPaymentId(UUID paymentId);
}
