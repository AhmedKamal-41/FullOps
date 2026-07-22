package com.ahmedali.fulfillops.inventory.service;

import com.ahmedali.fulfillops.inventory.cache.InventoryAvailabilityCache;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates release attempts, same bounded-retry shape as ReservationService. This is the
 * internal capability that the PaymentDeclined.v1 and fulfillment-cancellation listeners call once
 * the Payment Service and the cancellation saga wire this up.
 */
@Service
public class ReservationReleaseService {

  private static final Logger log = LoggerFactory.getLogger(ReservationReleaseService.class);

  private final ReservationReleaseTransaction releaseTransaction;
  private final InventoryAvailabilityCache availabilityCache;
  private final int maxAttempts;

  public ReservationReleaseService(
      ReservationReleaseTransaction releaseTransaction,
      InventoryAvailabilityCache availabilityCache,
      @Value("${app.inventory.reservation.max-attempts}") int maxAttempts) {
    this.releaseTransaction = releaseTransaction;
    this.availabilityCache = availabilityCache;
    this.maxAttempts = maxAttempts;
  }

  public ReleaseOutcome release(
      UUID orderId, ReleaseReasonCode reasonCode, UUID correlationId, UUID causationId) {
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        ReleaseOutcome outcome =
            releaseTransaction.attempt(orderId, reasonCode, correlationId, causationId);
        if (outcome instanceof ReleaseOutcome.Released released) {
          released.items().forEach(item -> availabilityCache.evict(item.sku()));
        }
        return outcome;
      } catch (OptimisticLockingFailureException conflict) {
        log.info(
            "optimistic lock conflict releasing order {}, attempt {} of {}",
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
