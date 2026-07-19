package com.ahmedali.fulfillops.inventory.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationItemRepository extends JpaRepository<ReservationItem, UUID> {

  List<ReservationItem> findByReservationId(UUID reservationId);
}
