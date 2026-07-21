package com.ahmedali.fulfillops.order.service;

import com.ahmedali.fulfillops.order.domain.IdempotencyRequest;
import com.ahmedali.fulfillops.order.domain.IdempotencyRequestRepository;
import com.ahmedali.fulfillops.order.domain.Order;
import com.ahmedali.fulfillops.order.domain.OrderRepository;
import com.ahmedali.fulfillops.order.domain.OrderStatus;
import com.ahmedali.fulfillops.order.domain.OrderStatusHistory;
import com.ahmedali.fulfillops.order.domain.OrderStatusHistoryRepository;
import com.ahmedali.fulfillops.order.messaging.OutboxEventWriter;
import com.ahmedali.fulfillops.order.web.dto.MoneyDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A separate bean (not just a method on OrderService) so its @Transactional boundary is a real
 * Spring-proxy call from the outside, not a same-class self-invocation that Spring would silently
 * ignore. Order, its items, the initial status history row, the OrderPlaced.v1 outbox event, and
 * the idempotency ledger row all commit together or not at all — see docs/adr/0003-outbox-inbox.md.
 * saveAndFlush on the last line forces the idempotency table's unique-constraint check to happen
 * here, synchronously, so a lost race throws where OrderService can catch it.
 */
@Component
public class OrderCreationTransaction {

  private static final String EVENT_TYPE = "OrderPlaced";
  private static final int EVENT_VERSION = 1;

  private final OrderRepository orderRepository;
  private final OrderStatusHistoryRepository orderStatusHistoryRepository;
  private final IdempotencyRequestRepository idempotencyRequestRepository;
  private final OutboxEventWriter outboxEventWriter;
  private final OperationsProjectionUpdater projectionUpdater;

  public OrderCreationTransaction(
      OrderRepository orderRepository,
      OrderStatusHistoryRepository orderStatusHistoryRepository,
      IdempotencyRequestRepository idempotencyRequestRepository,
      OutboxEventWriter outboxEventWriter,
      OperationsProjectionUpdater projectionUpdater) {
    this.orderRepository = orderRepository;
    this.orderStatusHistoryRepository = orderStatusHistoryRepository;
    this.idempotencyRequestRepository = idempotencyRequestRepository;
    this.outboxEventWriter = outboxEventWriter;
    this.projectionUpdater = projectionUpdater;
  }

  @Transactional
  public Order createNewOrder(
      UUID customerId,
      String idempotencyKey,
      OrderPricing.Computed computed,
      String requestFingerprint,
      UUID correlationId) {
    UUID orderId = UUID.randomUUID();

    Order order = new Order(orderId, customerId, computed.currencyCode(), computed.totalAmount());
    order.recordCorrelationId(correlationId);
    for (OrderPricing.ComputedItem item : computed.items()) {
      order.addItem(item.sku(), item.quantity(), item.unitPrice(), item.lineTotal());
    }
    orderRepository.save(order);

    orderStatusHistoryRepository.save(
        new OrderStatusHistory(orderId, OrderStatus.PENDING, null, order.getCreatedAt()));
    projectionUpdater.onOrderPlaced(order);

    outboxEventWriter.write(
        EVENT_TYPE,
        EVENT_VERSION,
        orderId,
        correlationId,
        null,
        orderPlacedPayload(customerId, idempotencyKey, computed));

    idempotencyRequestRepository.saveAndFlush(
        new IdempotencyRequest(customerId, idempotencyKey, requestFingerprint, orderId));

    return order;
  }

  private record OrderPlacedPayload(
      UUID customerId, String idempotencyKey, List<PayloadItem> items, MoneyDto totalAmount) {}

  private record PayloadItem(String sku, int quantity, MoneyDto unitPrice) {}

  private static OrderPlacedPayload orderPlacedPayload(
      UUID customerId, String idempotencyKey, OrderPricing.Computed computed) {
    List<PayloadItem> items =
        computed.items().stream()
            .map(
                item ->
                    new PayloadItem(
                        item.sku(),
                        item.quantity(),
                        toMoneyDto(computed.currencyCode(), item.unitPrice())))
            .toList();
    return new OrderPlacedPayload(
        customerId,
        idempotencyKey,
        items,
        toMoneyDto(computed.currencyCode(), computed.totalAmount()));
  }

  private static MoneyDto toMoneyDto(String currencyCode, BigDecimal amount) {
    return new MoneyDto(currencyCode, amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString());
  }
}
