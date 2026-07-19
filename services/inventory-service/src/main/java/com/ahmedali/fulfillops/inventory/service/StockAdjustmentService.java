package com.ahmedali.fulfillops.inventory.service;

import com.ahmedali.fulfillops.inventory.cache.InventoryAvailabilityCache;
import com.ahmedali.fulfillops.inventory.domain.AdjustmentReasonCode;
import com.ahmedali.fulfillops.inventory.domain.IdempotencyRequest;
import com.ahmedali.fulfillops.inventory.domain.IdempotencyRequestId;
import com.ahmedali.fulfillops.inventory.domain.IdempotencyRequestRepository;
import com.ahmedali.fulfillops.inventory.domain.InventoryAdjustment;
import com.ahmedali.fulfillops.inventory.domain.InventoryAdjustmentRepository;
import com.ahmedali.fulfillops.inventory.web.dto.AdjustStockRequest;
import com.ahmedali.fulfillops.inventory.web.dto.AdjustmentResponse;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates admin stock adjustments: an idempotency-key replay check (same shape as
 * ProductService), then a bounded retry loop around StockAdjustmentTransaction for the case where a
 * concurrent reservation on the same sku wins the StockLevel version race (same shape as
 * ReservationService).
 */
@Service
public class StockAdjustmentService {

  private static final Logger log = LoggerFactory.getLogger(StockAdjustmentService.class);

  private final IdempotencyRequestRepository idempotencyRequestRepository;
  private final InventoryAdjustmentRepository adjustmentRepository;
  private final StockAdjustmentTransaction stockAdjustmentTransaction;
  private final InventoryAvailabilityCache availabilityCache;
  private final int maxAttempts;

  public StockAdjustmentService(
      IdempotencyRequestRepository idempotencyRequestRepository,
      InventoryAdjustmentRepository adjustmentRepository,
      StockAdjustmentTransaction stockAdjustmentTransaction,
      InventoryAvailabilityCache availabilityCache,
      @Value("${app.inventory.reservation.max-attempts}") int maxAttempts) {
    this.idempotencyRequestRepository = idempotencyRequestRepository;
    this.adjustmentRepository = adjustmentRepository;
    this.stockAdjustmentTransaction = stockAdjustmentTransaction;
    this.availabilityCache = availabilityCache;
    this.maxAttempts = maxAttempts;
  }

  public AdjustmentResponse adjustStock(
      String actorId,
      String idempotencyKey,
      String sku,
      AdjustStockRequest request,
      UUID correlationId) {
    AdjustmentReasonCode reasonCode = parseReasonCode(request.reasonCode());
    if (request.changeQuantity() == 0) {
      throw new InvalidAdjustmentException("changeQuantity must not be zero");
    }

    String fingerprint = fingerprint(actorId, sku, request);
    IdempotencyRequestId idempotencyRequestId = new IdempotencyRequestId(actorId, idempotencyKey);

    Optional<IdempotencyRequest> existing =
        idempotencyRequestRepository.findById(idempotencyRequestId);
    if (existing.isPresent()) {
      return replayOrConflict(existing.get(), idempotencyKey, fingerprint);
    }

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        InventoryAdjustment adjustment =
            stockAdjustmentTransaction.attempt(
                actorId,
                idempotencyKey,
                sku,
                request.changeQuantity(),
                reasonCode.name(),
                request.reasonDetail(),
                correlationId,
                fingerprint);
        availabilityCache.evict(sku);
        return toResponse(adjustment);
      } catch (DataIntegrityViolationException lostRace) {
        Optional<IdempotencyRequest> winner =
            idempotencyRequestRepository.findById(idempotencyRequestId);
        if (winner.isPresent()) {
          return replayOrConflict(winner.get(), idempotencyKey, fingerprint);
        }
        throw lostRace;
      } catch (OptimisticLockingFailureException conflict) {
        log.info(
            "optimistic lock conflict adjusting sku {}, attempt {} of {}",
            sku,
            attempt,
            maxAttempts);
        if (attempt == maxAttempts) {
          throw new StockConcurrencyException("sku " + sku, maxAttempts, conflict);
        }
        RetryBackoff.pauseBeforeRetry(attempt);
      }
    }
    throw new IllegalStateException("unreachable: loop above always returns or throws");
  }

  private AdjustmentResponse replayOrConflict(
      IdempotencyRequest existing, String idempotencyKey, String fingerprint) {
    if (!existing.getRequestFingerprint().equals(fingerprint)) {
      throw new IdempotencyKeyConflictException(idempotencyKey);
    }
    InventoryAdjustment adjustment =
        adjustmentRepository
            .findById(existing.getReferenceId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "idempotency_requests row references a missing adjustment: "
                            + existing.getReferenceId()));
    return toResponse(adjustment);
  }

  private static AdjustmentReasonCode parseReasonCode(String reasonCode) {
    try {
      return AdjustmentReasonCode.valueOf(reasonCode);
    } catch (IllegalArgumentException notAKnownCode) {
      throw new InvalidAdjustmentException("unknown reasonCode: " + reasonCode);
    }
  }

  private static String fingerprint(String actorId, String sku, AdjustStockRequest request) {
    String canonical =
        actorId
            + '|'
            + sku
            + '|'
            + request.changeQuantity()
            + '|'
            + request.reasonCode()
            + '|'
            + request.reasonDetail();
    return RequestFingerprint.sha256Hex(canonical);
  }

  private static AdjustmentResponse toResponse(InventoryAdjustment adjustment) {
    return new AdjustmentResponse(
        adjustment.getAdjustmentId(),
        adjustment.getSku(),
        adjustment.getChangeQuantity(),
        adjustment.getQuantityBefore(),
        adjustment.getQuantityAfter(),
        adjustment.getReasonCode(),
        adjustment.getReasonDetail(),
        adjustment.getActor(),
        adjustment.getCreatedAt());
  }
}
