package com.ahmedali.fulfillops.order.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentActionHistoryRepository
    extends JpaRepository<IncidentActionHistory, UUID> {

  List<IncidentActionHistory> findByIncidentIdOrderByOccurredAt(UUID incidentId);

  List<IncidentActionHistory> findByIncidentIdIn(List<UUID> incidentIds);
}
