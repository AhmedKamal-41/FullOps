package com.ahmedali.fulfillops.payment.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A documented, deterministic simulator rule: the order amount doubles as a "test token" the same
 * way real card-processor sandboxes use magic amounts. See SimulatorPaymentProviderAdapter for how
 * a rule is applied, and V2__payments.sql for the seeded fictional demo amounts.
 */
@Entity
@Table(name = "simulator_rules")
public class SimulatorRule {

  @Id private UUID ruleId;

  private BigDecimal matchAmount;

  @Enumerated(EnumType.STRING)
  private SimulatorOutcome outcome;

  private int failingAttempts;
  private String description;

  protected SimulatorRule() {
    // JPA
  }

  public SimulatorRule(
      UUID ruleId,
      BigDecimal matchAmount,
      SimulatorOutcome outcome,
      int failingAttempts,
      String description) {
    this.ruleId = ruleId;
    this.matchAmount = matchAmount;
    this.outcome = outcome;
    this.failingAttempts = failingAttempts;
    this.description = description;
  }

  public SimulatorOutcome getOutcome() {
    return outcome;
  }

  public int getFailingAttempts() {
    return failingAttempts;
  }

  public String getDescription() {
    return description;
  }
}
