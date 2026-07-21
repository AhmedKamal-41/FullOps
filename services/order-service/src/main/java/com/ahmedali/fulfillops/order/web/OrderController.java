package com.ahmedali.fulfillops.order.web;

import com.ahmedali.fulfillops.order.service.OrderCancellationService;
import com.ahmedali.fulfillops.order.service.OrderService;
import com.ahmedali.fulfillops.order.web.dto.CancellationRequest;
import com.ahmedali.fulfillops.order.web.dto.CreateOrderRequest;
import com.ahmedali.fulfillops.order.web.dto.OrderResponse;
import com.ahmedali.fulfillops.order.web.dto.OrderSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
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
@RequestMapping("/api/v1/orders")
@Validated
public class OrderController {

  private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

  private final OrderService orderService;
  private final OrderCancellationService orderCancellationService;

  public OrderController(
      OrderService orderService, OrderCancellationService orderCancellationService) {
    this.orderService = orderService;
    this.orderCancellationService = orderCancellationService;
  }

  @Operation(
      summary = "Place a new order",
      description =
          "Requires an Idempotency-Key header. Replaying the same key with an identical request "
              + "returns the original order; reusing it with a different request returns 409.")
  @ApiResponse(
      responseCode = "201",
      description = "Order created (or replayed from a prior identical request with the same key)",
      content =
          @Content(
              examples =
                  @ExampleObject(
                      """
                    {
                      "orderId": "9f8e7d6c-5b4a-4321-8765-1a2b3c4d5e6f",
                      "customerId": "3a2b1c0d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
                      "status": "PENDING",
                      "items": [
                        {"sku": "WIDGET-BLUE-M", "quantity": 2,
                         "unitPrice": {"currencyCode": "USD", "amount": "19.99"},
                         "lineTotal": {"currencyCode": "USD", "amount": "39.98"}}
                      ],
                      "totalAmount": {"currencyCode": "USD", "amount": "39.98"},
                      "createdAt": "2026-07-19T14:32:05.123Z"
                    }
                    """)))
  @ApiResponse(
      responseCode = "409",
      description = "Idempotency-Key reused with a different request body")
  @PostMapping
  public ResponseEntity<OrderResponse> createOrder(
      @RequestHeader(IDEMPOTENCY_KEY_HEADER) @NotBlank @Size(max = 255) String idempotencyKey,
      @Valid @RequestBody CreateOrderRequest request,
      @AuthenticationPrincipal Jwt jwt,
      @RequestAttribute("correlationId") UUID correlationId) {
    UUID customerId = customerIdOf(jwt);
    OrderResponse order =
        orderService.createOrder(customerId, idempotencyKey, request, correlationId);
    return ResponseEntity.created(URI.create("/api/v1/orders/" + order.orderId())).body(order);
  }

  @Operation(
      summary = "Get an order",
      description =
          "The owning customer, or any OPERATOR/ADMIN, can read an order. Anyone else gets 404.")
  @GetMapping("/{orderId}")
  public OrderResponse getOrder(
      @PathVariable UUID orderId, @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
    UUID customerId = customerIdOf(jwt);
    boolean isStaff = hasAnyRole(authentication, "ROLE_OPERATOR", "ROLE_ADMIN");
    return orderService
        .getOrder(orderId, customerId, isStaff)
        .orElseThrow(() -> new OrderNotFoundException(orderId));
  }

  @Operation(summary = "List the current customer's own orders, newest first")
  @GetMapping
  public Page<OrderSummaryResponse> listMyOrders(
      @AuthenticationPrincipal Jwt jwt, Pageable pageable) {
    return orderService.listOrders(customerIdOf(jwt), pageable);
  }

  @Operation(
      summary = "Request cancellation of a nonterminal order",
      description =
          "Requires an Idempotency-Key header. A customer may only cancel their own order; an "
              + "operator/admin may cancel any order but must supply a reason. What happens next "
              + "depends on the order's current status — see docs/DOMAIN_MODEL.md.")
  @PostMapping("/{orderId}/cancellation-requests")
  public OrderResponse requestCancellation(
      @PathVariable UUID orderId,
      @RequestHeader(IDEMPOTENCY_KEY_HEADER) @NotBlank @Size(max = 255) String idempotencyKey,
      @Valid @RequestBody(required = false) CancellationRequest request,
      @AuthenticationPrincipal Jwt jwt,
      Authentication authentication,
      @RequestAttribute("correlationId") UUID correlationId) {
    boolean isStaff = hasAnyRole(authentication, "ROLE_OPERATOR", "ROLE_ADMIN");
    String reasonDetail = request == null ? null : request.reasonDetail();
    return orderCancellationService.requestCancellation(
        orderId, jwt.getSubject(), isStaff, idempotencyKey, reasonDetail, correlationId);
  }

  private static UUID customerIdOf(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  private static boolean hasAnyRole(Authentication authentication, String... roles) {
    for (GrantedAuthority authority : authentication.getAuthorities()) {
      for (String role : roles) {
        if (authority.getAuthority().equals(role)) {
          return true;
        }
      }
    }
    return false;
  }
}
