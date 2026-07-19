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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes exactly one release attempt for a reservation. InventoryReservation.version is what makes
 * two concurrent release attempts for the same order safe: both may read status=RESERVED, but only
 * one of them can win the flush that flips it to RELEASED — the loser's save throws, the whole
 * attempt (including any stock it already added back) rolls back, and ReservationReleaseService's
 * retry re-reads the now-committed RELEASED status and returns a no-op instead of adding stock
 * again. Stock rows are read via findBySkuForUpdate (a row lock) and locked in a fixed order
 * (sorted by sku) for the same reason ReservationTransaction does — see its Javadoc.
 */
@Component
public class ReservationReleaseTransaction {

  private static final String RELEASED_EVENT_TYPE = "InventoryReleased";
  private static final int EVENT_VERSION = 1;
  private static final String SYSTEM_ACTOR = "system";

  private final InventoryReservationRepository reservationRepository;
  private final ReservationItemRepository reservationItemRepository;
  private final StockLevelRepository stockLevelRepository;
  private final InventoryAdjustmentRepository adjustmentRepository;
  private final OutboxEventWriter outboxEventWriter;

  public ReservationReleaseTransaction(
      InventoryReservationRepository reservationRepository,
      ReservationItemRepository reservationItemRepository,
      StockLevelRepository stockLevelRepository,
      InventoryAdjustmentRepository adjustmentRepository,
      OutboxEventWriter outboxEventWriter) {
    this.reservationRepository = reservationRepository;
    this.reservationItemRepository = reservationItemRepository;
    this.stockLevelRepository = stockLevelRepository;
    this.adjustmentRepository = adjustmentRepository;
    this.outboxEventWriter = outboxEventWriter;
  }

  @Transactional
  public ReleaseOutcome attempt(
      UUID orderId, ReleaseReasonCode reasonCode, UUID correlationId, UUID causationId) {
    InventoryReservation reservation =
        reservationRepository
            .findByOrderId(orderId)
            .orElseThrow(() -> new ReservationNotFoundException(orderId));

    if (reservation.isReleased()) {
      return new ReleaseOutcome.AlreadyReleased(reservation.getReservationId());
    }

    List<ReservationItem> reservationItems =
        reservationItemRepository.findByReservationId(reservation.getReservationId()).stream()
            .sorted(Comparator.comparing(ReservationItem::getSku))
            .toList();

    for (ReservationItem item : reservationItems) {
      StockLevel stock =
          stockLevelRepository
              .findBySkuForUpdate(item.getSku())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "stock_level missing for reserved sku " + item.getSku()));
      int before = stock.getAvailableQuantity();
      stock.release(item.getQuantity());
      stockLevelRepository.saveAndFlush(stock);

      adjustmentRepository.save(
          new InventoryAdjustment(
              UUID.randomUUID(),
              item.getSku(),
              AdjustmentSource.RELEASE,
              item.getQuantity(),
              before,
              stock.getAvailableQuantity(),
              reasonCode.name(),
              null,
              SYSTEM_ACTOR,
              correlationId));
    }

    reservation.release();
    reservationRepository.saveAndFlush(reservation);

    List<RequestedItem> items =
        reservationItems.stream()
            .map(item -> new RequestedItem(item.getSku(), item.getQuantity()))
            .toList();

    outboxEventWriter.write(
        RELEASED_EVENT_TYPE,
        EVENT_VERSION,
        orderId,
        correlationId,
        causationId,
        new ReleasedPayload(
            reservation.getReservationId(), toPayloadItems(items), reasonCode.name()));

    return new ReleaseOutcome.Released(reservation.getReservationId(), items);
  }

  private static List<PayloadItem> toPayloadItems(List<RequestedItem> items) {
    return items.stream().map(item -> new PayloadItem(item.sku(), item.quantity())).toList();
  }

  private record ReleasedPayload(UUID reservationId, List<PayloadItem> items, String reasonCode) {}

  private record PayloadItem(String sku, int quantity) {}
}
