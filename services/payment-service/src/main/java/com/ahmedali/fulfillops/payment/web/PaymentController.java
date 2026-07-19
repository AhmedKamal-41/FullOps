package com.ahmedali.fulfillops.payment.web;

import com.ahmedali.fulfillops.payment.domain.Payment;
import com.ahmedali.fulfillops.payment.domain.PaymentAttempt;
import com.ahmedali.fulfillops.payment.domain.Refund;
import com.ahmedali.fulfillops.payment.domain.RefundReasonCode;
import com.ahmedali.fulfillops.payment.service.InvalidRefundRequestException;
import com.ahmedali.fulfillops.payment.service.PaymentNotFoundException;
import com.ahmedali.fulfillops.payment.service.PaymentQueryService;
import com.ahmedali.fulfillops.payment.service.RefundService;
import com.ahmedali.fulfillops.payment.web.dto.PaymentAttemptResponse;
import com.ahmedali.fulfillops.payment.web.dto.PaymentResponse;
import com.ahmedali.fulfillops.payment.web.dto.RefundRequest;
import com.ahmedali.fulfillops.payment.web.dto.RefundResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@Validated
public class PaymentController {

  private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

  private final PaymentQueryService paymentQueryService;
  private final RefundService refundService;

  public PaymentController(PaymentQueryService paymentQueryService, RefundService refundService) {
    this.paymentQueryService = paymentQueryService;
    this.refundService = refundService;
  }

  @GetMapping("/{paymentId}")
  public PaymentResponse getPayment(@PathVariable UUID paymentId) {
    Payment payment =
        paymentQueryService
            .findPayment(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    return toResponse(payment);
  }

  @GetMapping("/{paymentId}/attempts")
  public List<PaymentAttemptResponse> getAttempts(@PathVariable UUID paymentId) {
    return paymentQueryService.findAttempts(paymentId).stream()
        .map(PaymentController::toResponse)
        .toList();
  }

  @PostMapping("/{paymentId}/refunds")
  public ResponseEntity<RefundResponse> refund(
      @PathVariable UUID paymentId,
      @RequestHeader(IDEMPOTENCY_KEY_HEADER) @NotBlank @Size(max = 255) String idempotencyKey,
      @Valid @RequestBody RefundRequest request,
      @AuthenticationPrincipal Jwt jwt,
      @RequestAttribute("correlationId") UUID correlationId) {
    RefundReasonCode reasonCode = parseReasonCode(request.reasonCode());
    Refund refund =
        refundService.refund(
            jwt.getSubject(), idempotencyKey, paymentId, reasonCode, correlationId);
    return ResponseEntity.status(201).body(toResponse(refund));
  }

  private static RefundReasonCode parseReasonCode(String reasonCode) {
    try {
      return RefundReasonCode.valueOf(reasonCode);
    } catch (IllegalArgumentException notAKnownCode) {
      throw new InvalidRefundRequestException("unknown reasonCode: " + reasonCode);
    }
  }

  private static PaymentResponse toResponse(Payment payment) {
    return new PaymentResponse(
        payment.getPaymentId(),
        payment.getOrderId(),
        payment.getCustomerId(),
        payment.getAmount(),
        payment.getCurrencyCode(),
        payment.getStatus().name(),
        payment.getDeclineReasonCode(),
        payment.getDeclineReasonDetail(),
        payment.getCreatedAt(),
        payment.getUpdatedAt());
  }

  private static PaymentAttemptResponse toResponse(PaymentAttempt attempt) {
    return new PaymentAttemptResponse(
        attempt.getAttemptNumber(),
        attempt.getOutcome().name(),
        attempt.getDetail(),
        attempt.getCreatedAt());
  }

  private static RefundResponse toResponse(Refund refund) {
    return new RefundResponse(
        refund.getRefundId(),
        refund.getPaymentId(),
        refund.getAmount(),
        refund.getCurrencyCode(),
        refund.getReasonCode(),
        refund.getCreatedAt());
  }
}
