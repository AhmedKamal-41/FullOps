package com.ahmedali.fulfillops.inventory.service;

import com.ahmedali.fulfillops.inventory.domain.AdjustmentSource;
import com.ahmedali.fulfillops.inventory.domain.IdempotencyRequest;
import com.ahmedali.fulfillops.inventory.domain.IdempotencyRequestRepository;
import com.ahmedali.fulfillops.inventory.domain.InventoryAdjustment;
import com.ahmedali.fulfillops.inventory.domain.InventoryAdjustmentRepository;
import com.ahmedali.fulfillops.inventory.domain.ReferenceType;
import com.ahmedali.fulfillops.inventory.domain.StockLevel;
import com.ahmedali.fulfillops.inventory.domain.StockLevelRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes exactly one adjustment attempt, same split-bean shape as ProductCreationTransaction and
 * ReservationTransaction. Reads the row via findBySkuForUpdate (a row lock, not a plain read) for
 * the same reason ReservationTransaction does — see its Javadoc — so a concurrent reservation on
 * the same sku queues behind this adjustment instead of racing it.
 */
@Component
public class StockAdjustmentTransaction {

  private final StockLevelRepository stockLevelRepository;
  private final InventoryAdjustmentRepository adjustmentRepository;
  private final IdempotencyRequestRepository idempotencyRequestRepository;
  private final LowStockSignalEvaluator lowStockSignalEvaluator;

  public StockAdjustmentTransaction(
      StockLevelRepository stockLevelRepository,
      InventoryAdjustmentRepository adjustmentRepository,
      IdempotencyRequestRepository idempotencyRequestRepository,
      LowStockSignalEvaluator lowStockSignalEvaluator) {
    this.stockLevelRepository = stockLevelRepository;
    this.adjustmentRepository = adjustmentRepository;
    this.idempotencyRequestRepository = idempotencyRequestRepository;
    this.lowStockSignalEvaluator = lowStockSignalEvaluator;
  }

  @Transactional
  public InventoryAdjustment attempt(
      String actorId,
      String idempotencyKey,
      String sku,
      int changeQuantity,
      String reasonCode,
      String reasonDetail,
      UUID correlationId,
      String requestFingerprint) {
    StockLevel stock =
        stockLevelRepository
            .findBySkuForUpdate(sku)
            .orElseThrow(() -> new ProductNotFoundException(sku));

    int before = stock.getAvailableQuantity();
    try {
      stock.adjust(changeQuantity);
    } catch (IllegalStateException wouldGoNegative) {
      throw new InvalidAdjustmentException(wouldGoNegative.getMessage());
    }
    stockLevelRepository.saveAndFlush(stock);

    InventoryAdjustment adjustment =
        new InventoryAdjustment(
            UUID.randomUUID(),
            sku,
            AdjustmentSource.ADMIN_ADJUSTMENT,
            changeQuantity,
            before,
            stock.getAvailableQuantity(),
            reasonCode,
            reasonDetail,
            actorId,
            correlationId);
    adjustmentRepository.save(adjustment);
    lowStockSignalEvaluator.evaluate(
        sku, before, stock.getAvailableQuantity(), correlationId, null);

    idempotencyRequestRepository.saveAndFlush(
        new IdempotencyRequest(
            actorId,
            idempotencyKey,
            requestFingerprint,
            ReferenceType.ADJUSTMENT,
            adjustment.getAdjustmentId()));

    return adjustment;
  }
}
