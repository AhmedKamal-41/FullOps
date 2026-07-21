package com.ahmedali.fulfillops.payment.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

  List<PaymentAttempt> findByOrderIdOrderByAttemptNumber(UUID orderId);

  int countByOrderId(UUID orderId);

  int countByOrderIdAndOutcomeIn(UUID orderId, Collection<PaymentAttemptOutcome> outcomes);
}
