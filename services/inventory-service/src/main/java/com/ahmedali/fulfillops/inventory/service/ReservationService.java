package com.ahmedali.fulfillops.inventory.service;

import com.ahmedali.fulfillops.inventory.cache.InventoryAvailabilityCache;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates reservation attempts. Deliberately not @Transactional itself — see
 * ReservationTransaction for why a retry re-invokes that bean fresh (new transaction, new
 * persistence context) rather than looping inside one transaction after a failed flush.
 */
@Service
public class ReservationService {

  private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

  private final ReservationTransaction reservationTransaction;
  private final InventoryAvailabilityCache availabilityCache;
  private final int maxAttempts;

  public ReservationService(
      ReservationTransaction reservationTransaction,
      InventoryAvailabilityCache availabilityCache,
      @Value("${app.inventory.reservation.max-attempts}") int maxAttempts) {
    this.reservationTransaction = reservationTransaction;
    this.availabilityCache = availabilityCache;
    this.maxAttempts = maxAttempts;
  }

  public ReservationOutcome reserve(
      UUID orderId, List<RequestedItem> items, UUID correlationId, UUID causationId) {
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        ReservationOutcome outcome =
            reservationTransaction.attempt(orderId, items, correlationId, causationId);
        items.forEach(item -> availabilityCache.evict(item.sku()));
        return outcome;
      } catch (OptimisticLockingFailureException conflict) {
        log.info(
            "optimistic lock conflict reserving order {}, attempt {} of {}",
            orderId,
            attempt,
            maxAttempts);
        if (attempt == maxAttempts) {
          throw new StockConcurrencyException("order " + orderId, maxAttempts, conflict);
        }
        RetryBackoff.pauseBeforeRetry(attempt);
      }
    }
    throw new IllegalStateException("unreachable: loop above always returns or throws");
  }
}
