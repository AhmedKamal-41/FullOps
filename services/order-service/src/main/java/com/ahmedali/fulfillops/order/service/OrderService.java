package com.ahmedali.fulfillops.order.service;

import com.ahmedali.fulfillops.order.domain.IdempotencyRequest;
import com.ahmedali.fulfillops.order.domain.IdempotencyRequestId;
import com.ahmedali.fulfillops.order.domain.IdempotencyRequestRepository;
import com.ahmedali.fulfillops.order.domain.Order;
import com.ahmedali.fulfillops.order.domain.OrderRepository;
import com.ahmedali.fulfillops.order.web.dto.CreateOrderRequest;
import com.ahmedali.fulfillops.order.web.dto.MoneyDto;
import com.ahmedali.fulfillops.order.web.dto.OrderItemResponse;
import com.ahmedali.fulfillops.order.web.dto.OrderResponse;
import com.ahmedali.fulfillops.order.web.dto.OrderSummaryResponse;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Orchestrates order creation and reads. Deliberately not @Transactional itself — see
 * OrderCreationTransaction for why the actual insert lives in its own bean with its own transaction
 * boundary, which is what lets createOrder catch a lost idempotency-key race and recover from it
 * instead of the whole request failing.
 */
@Service
public class OrderService {

  private final OrderRepository orderRepository;
  private final IdempotencyRequestRepository idempotencyRequestRepository;
  private final OrderCreationTransaction orderCreationTransaction;

  public OrderService(
      OrderRepository orderRepository,
      IdempotencyRequestRepository idempotencyRequestRepository,
      OrderCreationTransaction orderCreationTransaction) {
    this.orderRepository = orderRepository;
    this.idempotencyRequestRepository = idempotencyRequestRepository;
    this.orderCreationTransaction = orderCreationTransaction;
  }

  public OrderResponse createOrder(
      UUID customerId, String idempotencyKey, CreateOrderRequest request, UUID correlationId) {
    OrderPricing.Computed computed = OrderPricing.compute(request);
    String fingerprint = fingerprint(customerId, computed);
    IdempotencyRequestId idempotencyRequestId =
        new IdempotencyRequestId(customerId, idempotencyKey);

    Optional<IdempotencyRequest> existing =
        idempotencyRequestRepository.findById(idempotencyRequestId);
    if (existing.isPresent()) {
      return replayOrConflict(existing.get(), idempotencyKey, fingerprint);
    }

    try {
      Order order =
          orderCreationTransaction.createNewOrder(
              customerId, idempotencyKey, computed, fingerprint, correlationId);
      return toResponse(order);
    } catch (DataIntegrityViolationException lostRace) {
      IdempotencyRequest winner =
          idempotencyRequestRepository.findById(idempotencyRequestId).orElseThrow(() -> lostRace);
      return replayOrConflict(winner, idempotencyKey, fingerprint);
    }
  }

  public Optional<OrderResponse> getOrder(
      UUID orderId, UUID requesterId, boolean requesterIsStaff) {
    return orderRepository
        .findById(orderId)
        .filter(order -> requesterIsStaff || order.getCustomerId().equals(requesterId))
        .map(OrderService::toResponse);
  }

  public Page<OrderSummaryResponse> listOrders(UUID customerId, Pageable pageable) {
    return orderRepository.findByCustomerId(customerId, pageable).map(OrderService::toSummary);
  }

  private OrderResponse replayOrConflict(
      IdempotencyRequest existing, String idempotencyKey, String fingerprint) {
    if (!existing.getRequestFingerprint().equals(fingerprint)) {
      throw new IdempotencyKeyConflictException(idempotencyKey);
    }
    Order order =
        orderRepository
            .findById(existing.getOrderId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "idempotency_requests row references a missing order: "
                            + existing.getOrderId()));
    return toResponse(order);
  }

  private static String fingerprint(UUID customerId, OrderPricing.Computed computed) {
    StringBuilder canonical = new StringBuilder();
    canonical.append(customerId).append('|').append(computed.currencyCode());
    computed.items().stream()
        .sorted(Comparator.comparing(OrderPricing.ComputedItem::sku))
        .forEach(
            item ->
                canonical
                    .append('|')
                    .append(item.sku())
                    .append(':')
                    .append(item.quantity())
                    .append(':')
                    .append(item.unitPrice()));
    return RequestFingerprint.sha256Hex(canonical.toString());
  }

  static OrderResponse toResponse(Order order) {
    var items =
        order.getItems().stream()
            .map(
                item ->
                    new OrderItemResponse(
                        item.getSku(),
                        item.getQuantity(),
                        toMoneyDto(order.getCurrencyCode(), item.getUnitPrice()),
                        toMoneyDto(order.getCurrencyCode(), item.getLineTotal())))
            .toList();
    return new OrderResponse(
        order.getOrderId(),
        order.getCustomerId(),
        order.getStatus().name(),
        items,
        toMoneyDto(order.getCurrencyCode(), order.getTotalAmount()),
        order.getCreatedAt(),
        order.getCorrelationId());
  }

  private static OrderSummaryResponse toSummary(Order order) {
    return new OrderSummaryResponse(
        order.getOrderId(),
        order.getStatus().name(),
        toMoneyDto(order.getCurrencyCode(), order.getTotalAmount()),
        order.getCreatedAt());
  }

  private static MoneyDto toMoneyDto(String currencyCode, BigDecimal amount) {
    return new MoneyDto(currencyCode, amount.toPlainString());
  }
}
