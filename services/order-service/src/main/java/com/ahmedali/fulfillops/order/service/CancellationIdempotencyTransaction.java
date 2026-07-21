package com.ahmedali.fulfillops.order.service;

import com.ahmedali.fulfillops.order.domain.CancellationIdempotencyRequest;
import com.ahmedali.fulfillops.order.domain.CancellationIdempotencyRequestRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records a cancellation request's Idempotency-Key in its own small transaction, separate from
 * OrderCancellationTransaction's saga-start logic. This is safe specifically because a cancellation
 * request is idempotent at the domain level (requesting it twice always converges to the same
 * outcome) — unlike order creation, a crash between the saga starting and this row being written
 * cannot create a duplicate resource, only a harmless redundant retry.
 */
@Component
public class CancellationIdempotencyTransaction {

  private final CancellationIdempotencyRequestRepository repository;

  public CancellationIdempotencyTransaction(CancellationIdempotencyRequestRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public void record(
      String actorId, String idempotencyKey, String requestFingerprint, UUID orderId) {
    repository.save(
        new CancellationIdempotencyRequest(actorId, idempotencyKey, requestFingerprint, orderId));
  }
}
