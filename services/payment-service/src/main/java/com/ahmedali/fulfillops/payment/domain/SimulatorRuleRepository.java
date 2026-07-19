package com.ahmedali.fulfillops.payment.domain;

import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SimulatorRuleRepository extends JpaRepository<SimulatorRule, java.util.UUID> {

  Optional<SimulatorRule> findByMatchAmount(BigDecimal matchAmount);
}
