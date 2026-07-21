package com.ahmedali.fulfillops.fulfillment.web;

import com.ahmedali.fulfillops.fulfillment.domain.Fulfillment;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentStatus;
import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentStatusHistory;
import com.ahmedali.fulfillops.fulfillment.service.FulfillmentCommandService;
import com.ahmedali.fulfillops.fulfillment.service.FulfillmentNotFoundException;
import com.ahmedali.fulfillops.fulfillment.service.FulfillmentQueryService;
import com.ahmedali.fulfillops.fulfillment.service.InvalidFulfillmentRequestException;
import com.ahmedali.fulfillops.fulfillment.web.dto.AdvanceFulfillmentRequest;
import com.ahmedali.fulfillops.fulfillment.web.dto.CancelFulfillmentRequest;
import com.ahmedali.fulfillops.fulfillment.web.dto.FulfillmentHistoryEntryResponse;
import com.ahmedali.fulfillops.fulfillment.web.dto.FulfillmentResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OPERATOR/ADMIN-only warehouse workflow endpoints — see SecurityConfig. Every command
 * (claim/status/cancel) requires an If-Match header carrying the fulfillment's current version, a
 * simplified numeric stand-in for a full RFC 7232 ETag: the caller gets it from a prior GET's
 * "version" field and must echo it back, so two operators working from different, stale copies of
 * the same fulfillment can never silently overwrite each other (see
 * FulfillmentCommandService/FulfillmentTransition).
 */
@RestController
@RequestMapping("/api/v1/fulfillments")
@Validated
public class FulfillmentController {

  private static final String IF_MATCH_HEADER = "If-Match";

  private final FulfillmentQueryService fulfillmentQueryService;
  private final FulfillmentCommandService fulfillmentCommandService;

  public FulfillmentController(
      FulfillmentQueryService fulfillmentQueryService,
      FulfillmentCommandService fulfillmentCommandService) {
    this.fulfillmentQueryService = fulfillmentQueryService;
    this.fulfillmentCommandService = fulfillmentCommandService;
  }

  @GetMapping
  public Page<FulfillmentResponse> list(
      @RequestParam(required = false) FulfillmentStatus status,
      @PageableDefault(sort = "slaDueAt") Pageable pageable) {
    return fulfillmentQueryService.list(status, pageable).map(FulfillmentController::toResponse);
  }

  @GetMapping("/{fulfillmentId}")
  public FulfillmentResponse get(@PathVariable UUID fulfillmentId) {
    return toResponse(
        fulfillmentQueryService
            .find(fulfillmentId)
            .orElseThrow(() -> new FulfillmentNotFoundException(fulfillmentId)));
  }

  @GetMapping("/{fulfillmentId}/history")
  public List<FulfillmentHistoryEntryResponse> history(@PathVariable UUID fulfillmentId) {
    return fulfillmentQueryService.history(fulfillmentId).stream()
        .map(FulfillmentController::toResponse)
        .toList();
  }

  @PostMapping("/{fulfillmentId}/claim")
  public FulfillmentResponse claim(
      @PathVariable UUID fulfillmentId,
      @RequestHeader(IF_MATCH_HEADER) String ifMatch,
      @AuthenticationPrincipal Jwt jwt) {
    Fulfillment fulfillment =
        fulfillmentCommandService.claim(fulfillmentId, parseVersion(ifMatch), jwt.getSubject());
    return toResponse(fulfillment);
  }

  @PatchMapping("/{fulfillmentId}/status")
  public FulfillmentResponse advance(
      @PathVariable UUID fulfillmentId,
      @RequestHeader(IF_MATCH_HEADER) String ifMatch,
      @Valid @RequestBody AdvanceFulfillmentRequest request,
      @AuthenticationPrincipal Jwt jwt,
      @RequestAttribute("correlationId") UUID correlationId) {
    Fulfillment fulfillment =
        fulfillmentCommandService.advance(
            fulfillmentId,
            parseVersion(ifMatch),
            request.newStatus(),
            request.trackingReference(),
            request.deliveredAt(),
            request.notes(),
            jwt.getSubject(),
            correlationId);
    return toResponse(fulfillment);
  }

  @PostMapping("/{fulfillmentId}/cancel")
  public FulfillmentResponse cancel(
      @PathVariable UUID fulfillmentId,
      @RequestHeader(IF_MATCH_HEADER) String ifMatch,
      @Valid @RequestBody CancelFulfillmentRequest request,
      @AuthenticationPrincipal Jwt jwt,
      @RequestAttribute("correlationId") UUID correlationId) {
    Fulfillment fulfillment =
        fulfillmentCommandService.cancel(
            fulfillmentId,
            parseVersion(ifMatch),
            request.reasonDetail(),
            jwt.getSubject(),
            correlationId);
    return toResponse(fulfillment);
  }

  private static long parseVersion(String ifMatch) {
    try {
      return Long.parseLong(ifMatch.trim());
    } catch (NumberFormatException notANumber) {
      throw new InvalidFulfillmentRequestException(
          "If-Match must be the fulfillment's current version number, got: " + ifMatch);
    }
  }

  private static FulfillmentResponse toResponse(Fulfillment fulfillment) {
    return new FulfillmentResponse(
        fulfillment.getFulfillmentId(),
        fulfillment.getOrderId(),
        fulfillment.getStatus().name(),
        fulfillment.getWarehouseId(),
        fulfillment.getAssigneeId(),
        fulfillment.getSlaDueAt(),
        fulfillment.getTrackingReference(),
        fulfillment.getDeliveredAt(),
        fulfillment.getCancellationReasonCode(),
        fulfillment.getCancellationReasonDetail(),
        fulfillment.getVersion(),
        fulfillment.getCreatedAt(),
        fulfillment.getUpdatedAt());
  }

  private static FulfillmentHistoryEntryResponse toResponse(FulfillmentStatusHistory entry) {
    return new FulfillmentHistoryEntryResponse(
        entry.getStatus().name(), entry.getActor(), entry.getNotes(), entry.getOccurredAt());
  }
}
