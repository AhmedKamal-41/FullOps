package com.ahmedali.fulfillops.order.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectionRebuildRunRepository extends JpaRepository<ProjectionRebuildRun, UUID> {

  Optional<ProjectionRebuildRun> findFirstByOrderByStartedAtDesc();
}
