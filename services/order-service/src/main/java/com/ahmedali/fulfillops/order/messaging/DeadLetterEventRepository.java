package com.ahmedali.fulfillops.order.messaging;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeadLetterEventRepository
    extends JpaRepository<DeadLetterEvent, DeadLetterEventId> {

  // Realistically at most one row per eventId within a single service: an eventId is only ever
  // consumed by whichever listener(s) subscribe to its specific topic, and each topic in this
  // codebase has exactly one listener per service.
  Optional<DeadLetterEvent> findFirstByIdEventId(UUID eventId);

  List<DeadLetterEvent> findByStatusOrderByCreatedAtDesc(DeadLetterEventStatus status);

  long countByStatus(DeadLetterEventStatus status);

  Optional<DeadLetterEvent> findFirstByStatusOrderByCreatedAtAsc(DeadLetterEventStatus status);
}
