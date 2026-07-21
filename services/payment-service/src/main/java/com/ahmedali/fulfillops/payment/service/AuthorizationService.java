package com.ahmedali.fulfillops.payment.service;

import com.ahmedali.fulfillops.payment.domain.OrderPaymentContext;
import com.ahmedali.fulfillops.payment.domain.OrderPaymentContextRepository;
import com.ahmedali.fulfillops.payment.domain.PaymentAttemptOutcome;
import com.ahmedali.fulfillops.payment.domain.PaymentAttemptRepository;
import com.ahmedali.fulfillops.payment.domain.PaymentRepository;
import com.ahmedali.fulfillops.payment.provider.ProviderAuthorizationOutcome;
import com.ahmedali.fulfillops.payment.provider.ProviderAuthorizationRequest;
import com.ahmedali.fulfillops.payment.resilience.PaymentAttemptListener;
import com.ahmedali.fulfillops.payment.resilience.PaymentAuthorizationClient;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates one authorization attempt for an order: looks up the local order context built from
 * OrderPlaced.v1, asks PaymentAuthorizationClient to call the provider (with retry and circuit
 * breaker), and persists whichever final business outcome comes back. A technical failure that
 * exhausts every retry attempt, or a call rejected by an open circuit, is deliberately not caught
 * here — it propagates to InventoryReservedListener, whose @RetryableTopic gives Kafka-level
 * redelivery a later, less contended, chance.
 */
@Service
public class AuthorizationService {

  private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);

  // Order Service's operations projection (Phase 9) tracks how many transient failures preceded
  // a final authorize/decline outcome, as a KPI proxy for payment technical-failure rate — see
  // AuthorizationTransaction and contracts/events/PaymentAuthorized.v1.schema.json.
  private static final List<PaymentAttemptOutcome> TECHNICAL_FAILURE_OUTCOMES =
      List.of(
          PaymentAttemptOutcome.TIMEOUT,
          PaymentAttemptOutcome.TEMPORARY_ERROR,
          PaymentAttemptOutcome.CIRCUIT_OPEN);

  private final OrderPaymentContextRepository orderPaymentContextRepository;
  private final PaymentRepository paymentRepository;
  private final PaymentAttemptRepository paymentAttemptRepository;
  private final PaymentAttemptRecorder paymentAttemptRecorder;
  private final PaymentAuthorizationClient paymentAuthorizationClient;
  private final AuthorizationTransaction authorizationTransaction;

  public AuthorizationService(
      OrderPaymentContextRepository orderPaymentContextRepository,
      PaymentRepository paymentRepository,
      PaymentAttemptRepository paymentAttemptRepository,
      PaymentAttemptRecorder paymentAttemptRecorder,
      PaymentAuthorizationClient paymentAuthorizationClient,
      AuthorizationTransaction authorizationTransaction) {
    this.orderPaymentContextRepository = orderPaymentContextRepository;
    this.paymentRepository = paymentRepository;
    this.paymentAttemptRepository = paymentAttemptRepository;
    this.paymentAttemptRecorder = paymentAttemptRecorder;
    this.paymentAuthorizationClient = paymentAuthorizationClient;
    this.authorizationTransaction = authorizationTransaction;
  }

  public void authorize(UUID orderId, UUID correlationId, UUID causationId) {
    if (paymentRepository.findByOrderId(orderId).isPresent()) {
      log.info("payment already decided for order {}, skipping", orderId);
      return;
    }

    OrderPaymentContext context =
        orderPaymentContextRepository
            .findById(orderId)
            .orElseThrow(() -> new OrderPaymentContextNotYetAvailableException(orderId));

    int startingAttemptNumber = paymentAttemptRepository.countByOrderId(orderId);
    ProviderAuthorizationRequest request =
        new ProviderAuthorizationRequest(
            orderId, context.getCustomerId(), context.getAmount(), context.getCurrencyCode(), 0);
    PaymentAttemptListener listener =
        (attemptNumber, outcome, detail) ->
            paymentAttemptRecorder.record(orderId, attemptNumber, outcome, detail);

    ProviderAuthorizationOutcome outcome =
        paymentAuthorizationClient.authorize(request, startingAttemptNumber, listener);

    // Every attempt this call made (and any from earlier redeliveries of the same order) is
    // already committed by paymentAttemptRecorder's own REQUIRES_NEW transaction by this point.
    int precedingTechnicalFailureCount =
        paymentAttemptRepository.countByOrderIdAndOutcomeIn(orderId, TECHNICAL_FAILURE_OUTCOMES);

    switch (outcome) {
      case ProviderAuthorizationOutcome.Approved ignored ->
          authorizationTransaction.recordApproved(
              orderId,
              context.getCustomerId(),
              context.getAmount(),
              context.getCurrencyCode(),
              precedingTechnicalFailureCount,
              correlationId,
              causationId);
      case ProviderAuthorizationOutcome.Declined declined ->
          authorizationTransaction.recordDeclined(
              orderId,
              context.getCustomerId(),
              context.getAmount(),
              context.getCurrencyCode(),
              declined.reasonCode(),
              declined.reasonDetail(),
              precedingTechnicalFailureCount,
              correlationId,
              causationId);
    }
  }
}
