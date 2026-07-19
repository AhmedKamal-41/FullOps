package com.ahmedali.fulfillops.inventory.service;

import com.ahmedali.fulfillops.inventory.cache.AvailabilitySnapshot;
import com.ahmedali.fulfillops.inventory.cache.InventoryAvailabilityCache;
import com.ahmedali.fulfillops.inventory.domain.StockLevel;
import com.ahmedali.fulfillops.inventory.domain.StockLevelRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Read-side of inventory: cache-aside availability lookups, backed by StockLevelRepository. */
@Service
public class InventoryQueryService {

  private final StockLevelRepository stockLevelRepository;
  private final InventoryAvailabilityCache availabilityCache;
  private final int defaultLowStockThreshold;

  public InventoryQueryService(
      StockLevelRepository stockLevelRepository,
      InventoryAvailabilityCache availabilityCache,
      @Value("${app.inventory.low-stock.default-threshold}") int defaultLowStockThreshold) {
    this.stockLevelRepository = stockLevelRepository;
    this.availabilityCache = availabilityCache;
    this.defaultLowStockThreshold = defaultLowStockThreshold;
  }

  public Optional<AvailabilitySnapshot> getAvailability(String sku) {
    Optional<AvailabilitySnapshot> cached = availabilityCache.get(sku);
    if (cached.isPresent()) {
      return cached;
    }

    Optional<StockLevel> stock = stockLevelRepository.findBySku(sku);
    if (stock.isEmpty()) {
      return Optional.empty();
    }

    AvailabilitySnapshot snapshot = toSnapshot(stock.get());
    availabilityCache.put(sku, snapshot);
    return Optional.of(snapshot);
  }

  public List<AvailabilitySnapshot> getLowStock(Integer threshold) {
    int effectiveThreshold = threshold != null ? threshold : defaultLowStockThreshold;
    return stockLevelRepository
        .findByAvailableQuantityLessThanEqualOrderBySku(effectiveThreshold)
        .stream()
        .map(InventoryQueryService::toSnapshot)
        .toList();
  }

  private static AvailabilitySnapshot toSnapshot(StockLevel stock) {
    return new AvailabilitySnapshot(
        stock.getSku(),
        stock.getAvailableQuantity(),
        stock.getReservedQuantity(),
        stock.getUpdatedAt());
  }
}
