package com.ahmedali.fulfillops.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ahmedali.fulfillops.order.domain.IdempotencyRequest;
import com.ahmedali.fulfillops.order.domain.IdempotencyRequestRepository;
import com.ahmedali.fulfillops.order.domain.Order;
import com.ahmedali.fulfillops.order.domain.OrderRepository;
import com.ahmedali.fulfillops.order.web.dto.CreateOrderItemRequest;
import com.ahmedali.fulfillops.order.web.dto.CreateOrderRequest;
import com.ahmedali.fulfillops.order.web.dto.MoneyDto;
import com.ahmedali.fulfillops.order.web.dto.OrderResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class OrderServiceTest {

  private final OrderRepository orderRepository = mock(OrderRepository.class);
  private final IdempotencyRequestRepository idempotencyRequestRepository =
      mock(IdempotencyRequestRepository.class);
  private final OrderCreationTransaction orderCreationTransaction =
      mock(OrderCreationTransaction.class);

  private OrderService orderService;

  private final UUID customerId = UUID.randomUUID();
  private final UUID correlationId = UUID.randomUUID();
  private final String idempotencyKey = "checkout-attempt-1";

  @BeforeEach
  void setUp() {
    orderService =
        new OrderService(orderRepository, idempotencyRequestRepository, orderCreationTransaction);
  }

  @Test
  void newIdempotencyKeyCreatesAnOrderThroughTheTransactionBean() {
    when(idempotencyRequestRepository.findById(any())).thenReturn(Optional.empty());
    Order created = anOrder();
    when(orderCreationTransaction.createNewOrder(
            eq(customerId), eq(idempotencyKey), any(), any(), eq(correlationId)))
        .thenReturn(created);

    OrderResponse response =
        orderService.createOrder(customerId, idempotencyKey, aRequest(), correlationId);

    assertThat(response.orderId()).isEqualTo(created.getOrderId());
    assertThat(response.status()).isEqualTo("PENDING");
  }

  @Test
  void replayingTheSameKeyAndPayloadReturnsTheOriginalOrderWithoutCreatingAnother() {
    Order original = anOrder();
    IdempotencyRequest existingRow = existingRowFor(original, aRequest());
    when(idempotencyRequestRepository.findById(any())).thenReturn(Optional.of(existingRow));
    when(orderRepository.findById(original.getOrderId())).thenReturn(Optional.of(original));

    OrderResponse response =
        orderService.createOrder(customerId, idempotencyKey, aRequest(), correlationId);

    assertThat(response.orderId()).isEqualTo(original.getOrderId());
    verify(orderCreationTransaction, never()).createNewOrder(any(), any(), any(), any(), any());
  }

  @Test
  void reusingTheKeyWithADifferentPayloadIsRejectedAsAConflict() {
    Order original = anOrder();
    CreateOrderRequest firstRequest = aRequest();
    IdempotencyRequest existingRow = existingRowFor(original, firstRequest);
    when(idempotencyRequestRepository.findById(any())).thenReturn(Optional.of(existingRow));

    CreateOrderRequest differentRequest =
        new CreateOrderRequest(
            List.of(new CreateOrderItemRequest("DIFFERENT-SKU", 5, new MoneyDto("USD", "5.00"))));

    assertThatThrownBy(
            () ->
                orderService.createOrder(
                    customerId, idempotencyKey, differentRequest, correlationId))
        .isInstanceOf(IdempotencyKeyConflictException.class);
  }

  @Test
  void losingTheCreationRaceReconcilesFromTheRowThatWon() {
    Order winner = anOrder();
    IdempotencyRequest winnerRow = existingRowFor(winner, aRequest());
    // First call (before attempting creation): no row yet. Second call (after the
    // transaction bean throws because another request won the race): the winner's row.
    when(idempotencyRequestRepository.findById(any()))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(winnerRow));
    when(orderCreationTransaction.createNewOrder(any(), any(), any(), any(), any()))
        .thenThrow(new DataIntegrityViolationException("duplicate key"));
    when(orderRepository.findById(winner.getOrderId())).thenReturn(Optional.of(winner));

    OrderResponse response =
        orderService.createOrder(customerId, idempotencyKey, aRequest(), correlationId);

    assertThat(response.orderId()).isEqualTo(winner.getOrderId());
  }

  @Test
  void ownerCanReadTheirOwnOrder() {
    Order order = anOrder();
    when(orderRepository.findById(order.getOrderId())).thenReturn(Optional.of(order));

    Optional<OrderResponse> result =
        orderService.getOrder(order.getOrderId(), order.getCustomerId(), false);

    assertThat(result).isPresent();
  }

  @Test
  void nonOwnerNonStaffCannotReadSomeoneElsesOrder() {
    Order order = anOrder();
    when(orderRepository.findById(order.getOrderId())).thenReturn(Optional.of(order));

    Optional<OrderResponse> result =
        orderService.getOrder(order.getOrderId(), UUID.randomUUID(), false);

    assertThat(result).isEmpty();
  }

  @Test
  void staffCanReadAnyonesOrder() {
    Order order = anOrder();
    when(orderRepository.findById(order.getOrderId())).thenReturn(Optional.of(order));

    Optional<OrderResponse> result =
        orderService.getOrder(order.getOrderId(), UUID.randomUUID(), true);

    assertThat(result).isPresent();
  }

  private CreateOrderRequest aRequest() {
    return new CreateOrderRequest(
        List.of(new CreateOrderItemRequest("WIDGET-BLUE-M", 2, new MoneyDto("USD", "19.99"))));
  }

  private Order anOrder() {
    Order order = new Order(UUID.randomUUID(), customerId, "USD", new BigDecimal("39.98"));
    order.addItem("WIDGET-BLUE-M", 2, new BigDecimal("19.99"), new BigDecimal("39.98"));
    return order;
  }

  private IdempotencyRequest existingRowFor(Order order, CreateOrderRequest request) {
    String fingerprint = fingerprintFor(request);
    return new IdempotencyRequest(customerId, idempotencyKey, fingerprint, order.getOrderId());
  }

  private String fingerprintFor(CreateOrderRequest request) {
    // Mirrors OrderService's private fingerprint format closely enough for these
    // tests: same customerId+currency+items in produces the same hash OrderService
    // itself would compute for an identical request, and a different one won't.
    OrderPricing.Computed computed = OrderPricing.compute(request);
    StringBuilder canonical = new StringBuilder();
    canonical.append(customerId).append('|').append(computed.currencyCode());
    computed.items().stream()
        .sorted(java.util.Comparator.comparing(OrderPricing.ComputedItem::sku))
        .forEach(
            item ->
                canonical
                    .append('|')
                    .append(item.sku())
                    .append(':')
                    .append(item.quantity())
                    .append(':')
                    .append(item.unitPrice()));
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      return java.util.HexFormat.of()
          .formatHex(
              digest.digest(
                  canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
