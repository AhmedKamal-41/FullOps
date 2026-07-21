package com.ahmedali.fulfillops.order.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LowStockSignalRepository extends JpaRepository<LowStockSignal, String> {

  List<LowStockSignal> findByBelowThresholdTrueOrderBySku();
}
