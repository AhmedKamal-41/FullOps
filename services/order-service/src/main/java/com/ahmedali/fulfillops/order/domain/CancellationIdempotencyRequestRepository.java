package com.ahmedali.fulfillops.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CancellationIdempotencyRequestRepository
    extends JpaRepository<CancellationIdempotencyRequest, CancellationIdempotencyRequestId> {}
