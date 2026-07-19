package com.ahmedali.fulfillops.payment.provider;

import com.ahmedali.fulfillops.payment.domain.SimulatorRule;
import com.ahmedali.fulfillops.payment.domain.SimulatorRuleRepository;
import org.springframework.stereotype.Component;

/**
 * The production PaymentProviderPort: a deterministic, fictional simulator, never a real payment
 * network. It never accepts, logs, or persists a card number, bank detail, or SSN — the only input
 * it looks at is the order's amount, matched against simulator_rules the same way real card
 * processor sandboxes use magic test amounts (see V2__payments.sql). Any amount with no matching
 * rule approves.
 */
@Component
public class SimulatorPaymentProviderAdapter implements PaymentProviderPort {

  private final SimulatorRuleRepository simulatorRuleRepository;

  public SimulatorPaymentProviderAdapter(SimulatorRuleRepository simulatorRuleRepository) {
    this.simulatorRuleRepository = simulatorRuleRepository;
  }

  @Override
  public ProviderAuthorizationOutcome authorize(ProviderAuthorizationRequest request) {
    SimulatorRule rule = simulatorRuleRepository.findByMatchAmount(request.amount()).orElse(null);
    if (rule == null) {
      return new ProviderAuthorizationOutcome.Approved();
    }

    return switch (rule.getOutcome()) {
      case APPROVE -> new ProviderAuthorizationOutcome.Approved();
      case DECLINE_INSUFFICIENT_FUNDS ->
          new ProviderAuthorizationOutcome.Declined(
              "SIMULATED_INSUFFICIENT_FUNDS", rule.getDescription());
      case DECLINE_CARD_DECLINED ->
          new ProviderAuthorizationOutcome.Declined(
              "SIMULATED_CARD_DECLINED", rule.getDescription());
      case TIMEOUT -> approveOrFailAgain(request, rule, "simulated provider timeout", true);
      case TEMPORARY_ERROR ->
          approveOrFailAgain(request, rule, "simulated transient provider error", false);
    };
  }

  private ProviderAuthorizationOutcome approveOrFailAgain(
      ProviderAuthorizationRequest request,
      SimulatorRule rule,
      String failureMessage,
      boolean isTimeout) {
    int failingAttempts = rule.getFailingAttempts();
    // 0 is the "never recovers on its own" sentinel (see V2__payments.sql) — every attempt
    // fails, not just attempt 0, so it can't be modeled as "attemptNumber <= 0".
    boolean stillFailing = failingAttempts == 0 || request.attemptNumber() <= failingAttempts;
    if (!stillFailing) {
      return new ProviderAuthorizationOutcome.Approved();
    }
    if (isTimeout) {
      throw new ProviderTimeoutException(failureMessage);
    }
    throw new ProviderTemporaryErrorException(failureMessage);
  }
}
