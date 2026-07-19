package com.ahmedali.fulfillops.inventory.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockLevelRepository extends JpaRepository<StockLevel, UUID> {

  Optional<StockLevel> findBySku(String sku);

  /**
   * Acquires a row-level lock (SELECT ... FOR UPDATE) instead of relying purely on the optimistic
   * version check. Every stock mutation (reservation, release, admin adjustment) reads through this
   * method, which is what turns concurrent access to the same SKU into a short queue instead of a
   * burst of doomed compare-and-swap attempts — see ReservationTransaction's Javadoc for why plain
   * optimistic retry alone starved under real contention.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from StockLevel s where s.sku = :sku")
  Optional<StockLevel> findBySkuForUpdate(@Param("sku") String sku);

  List<StockLevel> findByAvailableQuantityLessThanEqualOrderBySku(int threshold);
}
