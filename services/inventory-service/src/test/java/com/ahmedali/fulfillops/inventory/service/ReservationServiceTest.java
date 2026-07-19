package com.ahmedali.fulfillops.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ahmedali.fulfillops.inventory.cache.InventoryAvailabilityCache;
import com.ahmedali.fulfillops.inventory.domain.StockLevel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Covers the concurrency strategy documented on ReservationTransaction: a fresh, bounded retry per
 * optimistic-lock conflict, and a clear exception once every attempt is exhausted.
 */
class ReservationServiceTest {

  private static final int MAX_ATTEMPTS = 5;

  private final ReservationTransaction reservationTransaction = mock(ReservationTransaction.class);
  private final InventoryAvailabilityCache availabilityCache =
      mock(InventoryAvailabilityCache.class);

  private ReservationService reservationService;

  private final UUID orderId = UUID.randomUUID();
  private final UUID correlationId = UUID.randomUUID();
  private final UUID causationId = UUID.randomUUID();
  private final List<RequestedItem> items = List.of(new RequestedItem("WIDGET-BLUE-M", 2));

  @BeforeEach
  void setUp() {
    reservationService =
        new ReservationService(reservationTransaction, availabilityCache, MAX_ATTEMPTS);
  }

  @Test
  void aSuccessfulAttemptCallsTheTransactionBeanOnceAndEvictsTheCache() {
    ReservationOutcome.Reserved reserved =
        new ReservationOutcome.Reserved(UUID.randomUUID(), items);
    when(reservationTransaction.attempt(orderId, items, correlationId, causationId))
        .thenReturn(reserved);

    ReservationOutcome outcome =
        reservationService.reserve(orderId, items, correlationId, causationId);

    assertThat(outcome).isEqualTo(reserved);
    verify(reservationTransaction, times(1)).attempt(orderId, items, correlationId, causationId);
    verify(availabilityCache).evict("WIDGET-BLUE-M");
  }

  @Test
  void anOptimisticLockConflictRetriesUntilItSucceeds() {
    ReservationOutcome.Reserved reserved =
        new ReservationOutcome.Reserved(UUID.randomUUID(), items);
    when(reservationTransaction.attempt(orderId, items, correlationId, causationId))
        .thenThrow(conflict())
        .thenThrow(conflict())
        .thenReturn(reserved);

    ReservationOutcome outcome =
        reservationService.reserve(orderId, items, correlationId, causationId);

    assertThat(outcome).isEqualTo(reserved);
    verify(reservationTransaction, times(3)).attempt(orderId, items, correlationId, causationId);
  }

  @Test
  void exhaustingEveryAttemptThrowsStockConcurrencyExceptionWithoutEvictingTheCache() {
    when(reservationTransaction.attempt(orderId, items, correlationId, causationId))
        .thenThrow(conflict());

    assertThatThrownBy(() -> reservationService.reserve(orderId, items, correlationId, causationId))
        .isInstanceOf(StockConcurrencyException.class);

    verify(reservationTransaction, times(MAX_ATTEMPTS))
        .attempt(orderId, items, correlationId, causationId);
    verifyNoInteractions(availabilityCache);
  }

  private static ObjectOptimisticLockingFailureException conflict() {
    return new ObjectOptimisticLockingFailureException(StockLevel.class, "WIDGET-BLUE-M");
  }
}
