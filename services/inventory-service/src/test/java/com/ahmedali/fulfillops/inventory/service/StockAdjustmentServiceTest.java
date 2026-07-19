package com.ahmedali.fulfillops.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ahmedali.fulfillops.inventory.cache.InventoryAvailabilityCache;
import com.ahmedali.fulfillops.inventory.domain.AdjustmentSource;
import com.ahmedali.fulfillops.inventory.domain.IdempotencyRequest;
import com.ahmedali.fulfillops.inventory.domain.IdempotencyRequestRepository;
import com.ahmedali.fulfillops.inventory.domain.InventoryAdjustment;
import com.ahmedali.fulfillops.inventory.domain.InventoryAdjustmentRepository;
import com.ahmedali.fulfillops.inventory.domain.ReferenceType;
import com.ahmedali.fulfillops.inventory.domain.StockLevel;
import com.ahmedali.fulfillops.inventory.web.dto.AdjustStockRequest;
import com.ahmedali.fulfillops.inventory.web.dto.AdjustmentResponse;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class StockAdjustmentServiceTest {

  private static final int MAX_ATTEMPTS = 4;

  private final IdempotencyRequestRepository idempotencyRequestRepository =
      mock(IdempotencyRequestRepository.class);
  private final InventoryAdjustmentRepository adjustmentRepository =
      mock(InventoryAdjustmentRepository.class);
  private final StockAdjustmentTransaction stockAdjustmentTransaction =
      mock(StockAdjustmentTransaction.class);
  private final InventoryAvailabilityCache availabilityCache =
      mock(InventoryAvailabilityCache.class);

  private StockAdjustmentService stockAdjustmentService;

  private final String actorId = UUID.randomUUID().toString();
  private final String idempotencyKey = "restock-1";
  private final String sku = "WIDGET-BLUE-M";
  private final UUID correlationId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    stockAdjustmentService =
        new StockAdjustmentService(
            idempotencyRequestRepository,
            adjustmentRepository,
            stockAdjustmentTransaction,
            availabilityCache,
            MAX_ATTEMPTS);
  }

  @Test
  void newIdempotencyKeyAdjustsStockThroughTheTransactionBeanAndEvictsTheCache() {
    when(idempotencyRequestRepository.findById(any())).thenReturn(Optional.empty());
    InventoryAdjustment created = anAdjustment();
    when(stockAdjustmentTransaction.attempt(
            eq(actorId),
            eq(idempotencyKey),
            eq(sku),
            eq(10),
            eq("RESTOCK"),
            any(),
            eq(correlationId),
            any()))
        .thenReturn(created);

    AdjustmentResponse response =
        stockAdjustmentService.adjustStock(actorId, idempotencyKey, sku, aRequest(), correlationId);

    assertThat(response.adjustmentId()).isEqualTo(created.getAdjustmentId());
    verify(availabilityCache).evict(sku);
  }

  @Test
  void
      replayingTheSameKeyAndPayloadReturnsTheOriginalAdjustmentWithoutTouchingTheTransactionBean() {
    InventoryAdjustment original = anAdjustment();
    IdempotencyRequest existingRow = existingRowFor(original, aRequest());
    when(idempotencyRequestRepository.findById(any())).thenReturn(Optional.of(existingRow));
    when(adjustmentRepository.findById(original.getAdjustmentId()))
        .thenReturn(Optional.of(original));

    AdjustmentResponse response =
        stockAdjustmentService.adjustStock(actorId, idempotencyKey, sku, aRequest(), correlationId);

    assertThat(response.adjustmentId()).isEqualTo(original.getAdjustmentId());
    verify(stockAdjustmentTransaction, never())
        .attempt(any(), any(), any(), anyInt(), any(), any(), any(), any());
  }

  @Test
  void reusingTheKeyWithADifferentPayloadIsRejectedAsAConflict() {
    InventoryAdjustment original = anAdjustment();
    IdempotencyRequest existingRow = existingRowFor(original, aRequest());
    when(idempotencyRequestRepository.findById(any())).thenReturn(Optional.of(existingRow));

    AdjustStockRequest differentRequest = new AdjustStockRequest(99, "RESTOCK", null);

    assertThatThrownBy(
            () ->
                stockAdjustmentService.adjustStock(
                    actorId, idempotencyKey, sku, differentRequest, correlationId))
        .isInstanceOf(IdempotencyKeyConflictException.class);
  }

  @Test
  void unknownReasonCodeIsRejectedBeforeTouchingTheTransactionBean() {
    AdjustStockRequest badRequest = new AdjustStockRequest(10, "NOT_A_REAL_REASON", null);

    assertThatThrownBy(
            () ->
                stockAdjustmentService.adjustStock(
                    actorId, idempotencyKey, sku, badRequest, correlationId))
        .isInstanceOf(InvalidAdjustmentException.class);
    verifyNoInteractions(stockAdjustmentTransaction);
  }

  @Test
  void zeroChangeQuantityIsRejected() {
    AdjustStockRequest zeroRequest = new AdjustStockRequest(0, "RESTOCK", null);

    assertThatThrownBy(
            () ->
                stockAdjustmentService.adjustStock(
                    actorId, idempotencyKey, sku, zeroRequest, correlationId))
        .isInstanceOf(InvalidAdjustmentException.class);
  }

  @Test
  void exhaustingAllOptimisticLockRetriesThrowsStockConcurrencyException() {
    when(idempotencyRequestRepository.findById(any())).thenReturn(Optional.empty());
    when(stockAdjustmentTransaction.attempt(
            any(), any(), any(), anyInt(), any(), any(), any(), any()))
        .thenThrow(new ObjectOptimisticLockingFailureException(StockLevel.class, sku));

    assertThatThrownBy(
            () ->
                stockAdjustmentService.adjustStock(
                    actorId, idempotencyKey, sku, aRequest(), correlationId))
        .isInstanceOf(StockConcurrencyException.class);

    verify(stockAdjustmentTransaction, times(MAX_ATTEMPTS))
        .attempt(any(), any(), any(), anyInt(), any(), any(), any(), any());
  }

  private AdjustStockRequest aRequest() {
    return new AdjustStockRequest(10, "RESTOCK", "quarterly restock");
  }

  private InventoryAdjustment anAdjustment() {
    return new InventoryAdjustment(
        UUID.randomUUID(),
        sku,
        AdjustmentSource.ADMIN_ADJUSTMENT,
        10,
        0,
        10,
        "RESTOCK",
        "quarterly restock",
        actorId,
        correlationId);
  }

  private IdempotencyRequest existingRowFor(
      InventoryAdjustment adjustment, AdjustStockRequest request) {
    String fingerprint =
        RequestFingerprint.sha256Hex(
            actorId
                + '|'
                + sku
                + '|'
                + request.changeQuantity()
                + '|'
                + request.reasonCode()
                + '|'
                + request.reasonDetail());
    return new IdempotencyRequest(
        actorId,
        idempotencyKey,
        fingerprint,
        ReferenceType.ADJUSTMENT,
        adjustment.getAdjustmentId());
  }
}
