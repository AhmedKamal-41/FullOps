package com.ahmedali.fulfillops.inventory.service;

import com.ahmedali.fulfillops.inventory.domain.AdjustmentSource;
import com.ahmedali.fulfillops.inventory.domain.InventoryAdjustment;
import com.ahmedali.fulfillops.inventory.domain.InventoryAdjustmentRepository;
import com.ahmedali.fulfillops.inventory.domain.InventoryReservation;
import com.ahmedali.fulfillops.inventory.domain.InventoryReservationRepository;
import com.ahmedali.fulfillops.inventory.domain.ReservationItem;
import com.ahmedali.fulfillops.inventory.domain.ReservationItemRepository;
import com.ahmedali.fulfillops.inventory.domain.StockLevel;
import com.ahmedali.fulfillops.inventory.domain.StockLevelRepository;
import com.ahmedali.fulfillops.inventory.messaging.OutboxEventWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes exactly one reservation attempt: read every requested SKU's current stock, and either
 * reject the whole order (nothing sufficient enough is left untouched) or reserve every item and
 * write the InventoryReserved.v1/InventoryRejected.v1 outbox event, all in one transaction.
 *
 * <p>Rows are read via {@code findBySkuForUpdate}, which takes a row-level lock (SELECT ... FOR
 * UPDATE), not a plain read. An earlier version used a plain read and relied only on the @Version
 * optimistic check plus a bounded retry to resolve conflicts — under real contention (many orders
 * racing for the last units of one SKU) that let every contender read the same stale state at once,
 * so all but one lost the optimistic check every round, and a genuinely unlucky order could exhaust
 * its whole retry budget (caught by ReservationConcurrencyIT, which drives 10 concurrent orders at
 * 5 units of stock). Locking the row turns that race into a short queue instead: only one
 * transaction holds the row at a time, so the transaction that gets it always sees fresh state and
 * its later @Version-checked save practically never conflicts. The @Version column and
 * ReservationService's bounded retry stay in place as defense-in-depth, not as the primary
 * mechanism. Items are locked in a fixed order (sorted by sku) so two reservations that both touch
 * multiple overlapping SKUs can never deadlock by acquiring their locks in opposite orders.
 */
@Component
public class ReservationTransaction {

  private static final String RESERVED_EVENT_TYPE = "InventoryReserved";
  private static final String REJECTED_EVENT_TYPE = "InventoryRejected";
  private static final int EVENT_VERSION = 1;
  private static final String INSUFFICIENT_STOCK_REASON = "INSUFFICIENT_STOCK";
  private static final String RESERVATION_REASON_CODE = "ORDER_RESERVATION";
  private static final String SYSTEM_ACTOR = "system";

  private final StockLevelRepository stockLevelRepository;
  private final InventoryReservationRepository reservationRepository;
  private final ReservationItemRepository reservationItemRepository;
  private final InventoryAdjustmentRepository adjustmentRepository;
  private final OutboxEventWriter outboxEventWriter;

  public ReservationTransaction(
      StockLevelRepository stockLevelRepository,
      InventoryReservationRepository reservationRepository,
      ReservationItemRepository reservationItemRepository,
      InventoryAdjustmentRepository adjustmentRepository,
      OutboxEventWriter outboxEventWriter) {
    this.stockLevelRepository = stockLevelRepository;
    this.reservationRepository = reservationRepository;
    this.reservationItemRepository = reservationItemRepository;
    this.adjustmentRepository = adjustmentRepository;
    this.outboxEventWriter = outboxEventWriter;
  }

  @Transactional
  public ReservationOutcome attempt(
      UUID orderId, List<RequestedItem> unsortedItems, UUID correlationId, UUID causationId) {
    List<RequestedItem> items =
        unsortedItems.stream().sorted(Comparator.comparing(RequestedItem::sku)).toList();
    Map<String, StockLevel> sufficientStockBySku = new LinkedHashMap<>();
    List<ReservationOutcome.RejectedItem> rejections = new ArrayList<>();

    for (RequestedItem item : items) {
      StockLevel stock = stockLevelRepository.findBySkuForUpdate(item.sku()).orElse(null);
      int currentlyAvailable = stock == null ? 0 : stock.getAvailableQuantity();
      if (stock == null || !stock.hasAvailable(item.quantity())) {
        rejections.add(
            new ReservationOutcome.RejectedItem(item.sku(), item.quantity(), currentlyAvailable));
      } else {
        sufficientStockBySku.put(item.sku(), stock);
      }
    }

    if (!rejections.isEmpty()) {
      outboxEventWriter.write(
          REJECTED_EVENT_TYPE,
          EVENT_VERSION,
          orderId,
          correlationId,
          causationId,
          new RejectedPayload(rejections, INSUFFICIENT_STOCK_REASON));
      return new ReservationOutcome.Rejected(rejections);
    }

    UUID reservationId = UUID.randomUUID();
    reservationRepository.save(new InventoryReservation(reservationId, orderId));

    for (RequestedItem item : items) {
      StockLevel stock = sufficientStockBySku.get(item.sku());
      int before = stock.getAvailableQuantity();
      stock.reserve(item.quantity());
      stockLevelRepository.saveAndFlush(stock);

      reservationItemRepository.save(
          new ReservationItem(UUID.randomUUID(), reservationId, item.sku(), item.quantity()));
      adjustmentRepository.save(
          new InventoryAdjustment(
              UUID.randomUUID(),
              item.sku(),
              AdjustmentSource.RESERVATION,
              -item.quantity(),
              before,
              stock.getAvailableQuantity(),
              RESERVATION_REASON_CODE,
              null,
              SYSTEM_ACTOR,
              correlationId));
    }

    outboxEventWriter.write(
        RESERVED_EVENT_TYPE,
        EVENT_VERSION,
        orderId,
        correlationId,
        causationId,
        new ReservedPayload(reservationId, toPayloadItems(items)));

    return new ReservationOutcome.Reserved(reservationId, items);
  }

  private static List<PayloadItem> toPayloadItems(List<RequestedItem> items) {
    return items.stream().map(item -> new PayloadItem(item.sku(), item.quantity())).toList();
  }

  private record ReservedPayload(UUID reservationId, List<PayloadItem> items) {}

  private record PayloadItem(String sku, int quantity) {}

  private record RejectedPayload(List<ReservationOutcome.RejectedItem> items, String reasonCode) {}
}
