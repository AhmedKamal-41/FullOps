package com.ahmedali.fulfillops.payment.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ahmedali.fulfillops.payment.domain.OrderPaymentContext;
import com.ahmedali.fulfillops.payment.domain.OrderPaymentContextRepository;
import com.ahmedali.fulfillops.payment.domain.Payment;
import com.ahmedali.fulfillops.payment.domain.PaymentAttemptRepository;
import com.ahmedali.fulfillops.payment.domain.PaymentRepository;
import com.ahmedali.fulfillops.payment.provider.ProviderAuthorizationOutcome;
import com.ahmedali.fulfillops.payment.resilience.PaymentAuthorizationClient;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Orchestration logic only — the real retry/circuit-breaker mechanics are covered by
 * PaymentAuthorizationClientTest, and the real provider decision by
 * SimulatorPaymentProviderAdapterTest. PaymentAuthorizationClient is mocked here so this test
 * controls the outcome directly.
 */
class AuthorizationServiceTest {

  private final OrderPaymentContextRepository orderPaymentContextRepository =
      mock(OrderPaymentContextRepository.class);
  private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
  private final PaymentAttemptRepository paymentAttemptRepository =
      mock(PaymentAttemptRepository.class);
  private final PaymentAttemptRecorder paymentAttemptRecorder = mock(PaymentAttemptRecorder.class);
  private final PaymentAuthorizationClient paymentAuthorizationClient =
      mock(PaymentAuthorizationClient.class);
  private final AuthorizationTransaction authorizationTransaction =
      mock(AuthorizationTransaction.class);

  private AuthorizationService authorizationService;

  private final UUID orderId = UUID.randomUUID();
  private final UUID customerId = UUID.randomUUID();
  private final UUID correlationId = UUID.randomUUID();
  private final UUID causationId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    authorizationService =
        new AuthorizationService(
            orderPaymentContextRepository,
            paymentRepository,
            paymentAttemptRepository,
            paymentAttemptRecorder,
            paymentAuthorizationClient,
            authorizationTransaction);
  }

  @Test
  void aPaymentThatAlreadyExistsForTheOrderIsSkippedWithoutCallingTheProvider() {
    when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(mock(Payment.class)));

    authorizationService.authorize(orderId, correlationId, causationId);

    verifyNoInteractions(paymentAuthorizationClient, authorizationTransaction);
  }

  @Test
  void noOrderContextYetThrowsARetryableException() {
    when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.empty());
    when(orderPaymentContextRepository.findById(orderId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> authorizationService.authorize(orderId, correlationId, causationId))
        .isInstanceOf(OrderPaymentContextNotYetAvailableException.class);

    verifyNoInteractions(paymentAuthorizationClient, authorizationTransaction);
  }

  @Test
  void anApprovedOutcomeIsPersistedThroughAuthorizationTransaction() {
    stubContext(new BigDecimal("50.00"));
    when(paymentAuthorizationClient.authorize(any(), anyInt(), any()))
        .thenReturn(new ProviderAuthorizationOutcome.Approved());

    authorizationService.authorize(orderId, correlationId, causationId);

    verify(authorizationTransaction)
        .recordApproved(
            orderId, customerId, new BigDecimal("50.00"), "USD", correlationId, causationId);
    verify(authorizationTransaction, never())
        .recordDeclined(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void aDeclinedOutcomeIsPersistedWithItsReasonThroughAuthorizationTransaction() {
    stubContext(new BigDecimal("1.00"));
    when(paymentAuthorizationClient.authorize(any(), anyInt(), any()))
        .thenReturn(
            new ProviderAuthorizationOutcome.Declined(
                "SIMULATED_INSUFFICIENT_FUNDS", "not enough funds"));

    authorizationService.authorize(orderId, correlationId, causationId);

    verify(authorizationTransaction)
        .recordDeclined(
            orderId,
            customerId,
            new BigDecimal("1.00"),
            "USD",
            "SIMULATED_INSUFFICIENT_FUNDS",
            "not enough funds",
            correlationId,
            causationId);
    verify(authorizationTransaction, never())
        .recordApproved(any(), any(), any(), any(), any(), any());
  }

  @Test
  void attemptNumberingContinuesFromHowManyAttemptsAlreadyExistForTheOrder() {
    stubContext(new BigDecimal("50.00"));
    when(paymentAttemptRepository.countByOrderId(orderId)).thenReturn(2);
    when(paymentAuthorizationClient.authorize(any(), eq(2), any()))
        .thenReturn(new ProviderAuthorizationOutcome.Approved());

    authorizationService.authorize(orderId, correlationId, causationId);

    verify(paymentAuthorizationClient).authorize(any(), eq(2), any());
  }

  private void stubContext(BigDecimal amount) {
    when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.empty());
    when(orderPaymentContextRepository.findById(orderId))
        .thenReturn(
            Optional.of(
                new OrderPaymentContext(orderId, customerId, amount, "USD", correlationId)));
  }
}
