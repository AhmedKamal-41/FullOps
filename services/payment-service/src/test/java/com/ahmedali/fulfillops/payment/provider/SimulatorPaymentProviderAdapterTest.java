package com.ahmedali.fulfillops.payment.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.ahmedali.fulfillops.payment.domain.SimulatorOutcome;
import com.ahmedali.fulfillops.payment.domain.SimulatorRule;
import com.ahmedali.fulfillops.payment.domain.SimulatorRuleRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit-tests the deterministic decision rule in isolation from Spring/Postgres:
 * SimulatorRuleRepository is mocked so every branch of SimulatorPaymentProviderAdapter's switch is
 * exercised directly, without depending on the seeded V2__payments.sql rows (those are proven
 * end-to-end separately by AuthorizationIT).
 */
@ExtendWith(MockitoExtension.class)
class SimulatorPaymentProviderAdapterTest {

  @Mock private SimulatorRuleRepository simulatorRuleRepository;

  @Test
  void anAmountWithNoMatchingRuleApproves() {
    SimulatorPaymentProviderAdapter adapter =
        new SimulatorPaymentProviderAdapter(simulatorRuleRepository);
    BigDecimal amount = new BigDecimal("42.00");
    when(simulatorRuleRepository.findByMatchAmount(amount)).thenReturn(Optional.empty());

    ProviderAuthorizationOutcome outcome = adapter.authorize(request(amount, 1));

    assertThat(outcome).isInstanceOf(ProviderAuthorizationOutcome.Approved.class);
  }

  @Test
  void aDeclineRuleReturnsADeclinedOutcomeWithTheMatchingReasonCode() {
    SimulatorPaymentProviderAdapter adapter =
        new SimulatorPaymentProviderAdapter(simulatorRuleRepository);
    BigDecimal amount = new BigDecimal("1.00");
    when(simulatorRuleRepository.findByMatchAmount(amount))
        .thenReturn(
            Optional.of(rule(SimulatorOutcome.DECLINE_INSUFFICIENT_FUNDS, 0, "insufficient")));

    ProviderAuthorizationOutcome outcome = adapter.authorize(request(amount, 1));

    assertThat(outcome).isInstanceOf(ProviderAuthorizationOutcome.Declined.class);
    ProviderAuthorizationOutcome.Declined declined =
        (ProviderAuthorizationOutcome.Declined) outcome;
    assertThat(declined.reasonCode()).isEqualTo("SIMULATED_INSUFFICIENT_FUNDS");
    assertThat(declined.reasonDetail()).isEqualTo("insufficient");
  }

  @Test
  void aTimeoutRuleThrowsWhileTheAttemptNumberIsWithinFailingAttempts() {
    SimulatorPaymentProviderAdapter adapter =
        new SimulatorPaymentProviderAdapter(simulatorRuleRepository);
    BigDecimal amount = new BigDecimal("9997.00");
    when(simulatorRuleRepository.findByMatchAmount(amount))
        .thenReturn(Optional.of(rule(SimulatorOutcome.TIMEOUT, 2, "flaky")));

    assertThatThrownBy(() -> adapter.authorize(request(amount, 1)))
        .isInstanceOf(ProviderTimeoutException.class);
    assertThatThrownBy(() -> adapter.authorize(request(amount, 2)))
        .isInstanceOf(ProviderTimeoutException.class);
    assertThat(adapter.authorize(request(amount, 3)))
        .isInstanceOf(ProviderAuthorizationOutcome.Approved.class);
  }

  @Test
  void aTemporaryErrorRuleWithZeroFailingAttemptsNeverRecoversOnItsOwn() {
    SimulatorPaymentProviderAdapter adapter =
        new SimulatorPaymentProviderAdapter(simulatorRuleRepository);
    BigDecimal amount = new BigDecimal("9998.00");
    when(simulatorRuleRepository.findByMatchAmount(amount))
        .thenReturn(Optional.of(rule(SimulatorOutcome.TEMPORARY_ERROR, 0, "always down")));

    assertThatThrownBy(() -> adapter.authorize(request(amount, 1)))
        .isInstanceOf(ProviderTemporaryErrorException.class);
    assertThatThrownBy(() -> adapter.authorize(request(amount, 50)))
        .isInstanceOf(ProviderTemporaryErrorException.class);
  }

  private static ProviderAuthorizationRequest request(BigDecimal amount, int attemptNumber) {
    return new ProviderAuthorizationRequest(
        UUID.randomUUID(), UUID.randomUUID(), amount, "USD", attemptNumber);
  }

  private static SimulatorRule rule(
      SimulatorOutcome outcome, int failingAttempts, String description) {
    return new SimulatorRule(
        UUID.randomUUID(), new BigDecimal("1.00"), outcome, failingAttempts, description);
  }
}
