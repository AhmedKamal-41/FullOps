package com.ahmedali.fulfillops.inventory.web;

import com.ahmedali.fulfillops.inventory.cache.AvailabilitySnapshot;
import com.ahmedali.fulfillops.inventory.service.InventoryQueryService;
import com.ahmedali.fulfillops.inventory.service.ProductNotFoundException;
import com.ahmedali.fulfillops.inventory.service.StockAdjustmentService;
import com.ahmedali.fulfillops.inventory.web.dto.AdjustStockRequest;
import com.ahmedali.fulfillops.inventory.web.dto.AdjustmentResponse;
import com.ahmedali.fulfillops.inventory.web.dto.AvailabilityResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventory")
@Validated
public class InventoryController {

  private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

  private final StockAdjustmentService stockAdjustmentService;
  private final InventoryQueryService inventoryQueryService;

  public InventoryController(
      StockAdjustmentService stockAdjustmentService, InventoryQueryService inventoryQueryService) {
    this.stockAdjustmentService = stockAdjustmentService;
    this.inventoryQueryService = inventoryQueryService;
  }

  @PostMapping("/{sku}/adjustments")
  public ResponseEntity<AdjustmentResponse> adjustStock(
      @PathVariable String sku,
      @RequestHeader(IDEMPOTENCY_KEY_HEADER) @NotBlank @Size(max = 255) String idempotencyKey,
      @Valid @RequestBody AdjustStockRequest request,
      @AuthenticationPrincipal Jwt jwt,
      @RequestAttribute("correlationId") UUID correlationId) {
    AdjustmentResponse adjustment =
        stockAdjustmentService.adjustStock(
            jwt.getSubject(), idempotencyKey, sku, request, correlationId);
    return ResponseEntity.status(201).body(adjustment);
  }

  @GetMapping("/{sku}")
  public AvailabilityResponse getAvailability(@PathVariable String sku) {
    AvailabilitySnapshot snapshot =
        inventoryQueryService
            .getAvailability(sku)
            .orElseThrow(() -> new ProductNotFoundException(sku));
    return toResponse(snapshot);
  }

  @GetMapping("/low-stock")
  public List<AvailabilityResponse> getLowStock(@RequestParam(required = false) Integer threshold) {
    return inventoryQueryService.getLowStock(threshold).stream()
        .map(InventoryController::toResponse)
        .toList();
  }

  private static AvailabilityResponse toResponse(AvailabilitySnapshot snapshot) {
    return new AvailabilityResponse(
        snapshot.sku(),
        snapshot.availableQuantity(),
        snapshot.reservedQuantity(),
        snapshot.updatedAt());
  }
}
