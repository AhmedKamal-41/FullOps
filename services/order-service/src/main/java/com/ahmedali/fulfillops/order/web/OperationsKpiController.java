package com.ahmedali.fulfillops.order.web;

import com.ahmedali.fulfillops.order.domain.LowStockSignal;
import com.ahmedali.fulfillops.order.domain.LowStockSignalRepository;
import com.ahmedali.fulfillops.order.domain.TimeSeriesInterval;
import com.ahmedali.fulfillops.order.service.InvalidDateRangeException;
import com.ahmedali.fulfillops.order.service.KpiOverviewService;
import com.ahmedali.fulfillops.order.service.KpiTimeSeriesService;
import com.ahmedali.fulfillops.order.service.StageDurationKpiService;
import com.ahmedali.fulfillops.order.web.dto.BacklogResponse;
import com.ahmedali.fulfillops.order.web.dto.KpiOverviewResponse;
import com.ahmedali.fulfillops.order.web.dto.KpiTimeSeriesResponse;
import com.ahmedali.fulfillops.order.web.dto.LowStockSignalResponse;
import com.ahmedali.fulfillops.order.web.dto.StageDurationKpiResponse;
import com.ahmedali.fulfillops.order.web.dto.StuckOrdersResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * KPI reads for the operations console. OPERATOR/ADMIN only (see SecurityConfig). from/to are UTC
 * instants, validated so a client can't silently get an empty/nonsensical window back — see
 * docs/KPI_DICTIONARY.md for what every number here means and its exact time-window convention.
 */
@RestController
@RequestMapping("/api/v1/ops")
public class OperationsKpiController {

  private final KpiOverviewService overviewService;
  private final KpiTimeSeriesService timeSeriesService;
  private final StageDurationKpiService stageDurationKpiService;
  private final LowStockSignalRepository lowStockSignalRepository;

  public OperationsKpiController(
      KpiOverviewService overviewService,
      KpiTimeSeriesService timeSeriesService,
      StageDurationKpiService stageDurationKpiService,
      LowStockSignalRepository lowStockSignalRepository) {
    this.overviewService = overviewService;
    this.timeSeriesService = timeSeriesService;
    this.stageDurationKpiService = stageDurationKpiService;
    this.lowStockSignalRepository = lowStockSignalRepository;
  }

  @Operation(
      summary = "Order counts, rejection/decline rates, DLT/outbox backlog, recovery rates",
      description = "Every field's exact formula is documented in docs/KPI_DICTIONARY.md.")
  @ApiResponse(
      responseCode = "200",
      content =
          @Content(
              examples =
                  @ExampleObject(
                      """
                    {
                      "from": "2026-07-01T00:00:00Z",
                      "to": "2026-07-21T00:00:00Z",
                      "ordersReceived": 412,
                      "ordersCompleted": 350,
                      "ordersCancelled": 40,
                      "inventoryRejections": 12,
                      "inventoryRejectionRate": 0.0291,
                      "inventoryRejectionReasons": [
                        {"reasonCode": "INSUFFICIENT_STOCK", "orderCount": 12}
                      ],
                      "paymentDeclines": 18,
                      "paymentDeclineRate": 0.045,
                      "paymentDeclineReasons": [
                        {"reasonCode": "SIMULATED_INSUFFICIENT_FUNDS", "orderCount": 15},
                        {"reasonCode": "SIMULATED_CARD_DECLINED", "orderCount": 3}
                      ],
                      "paymentTechnicalFailureRate": 0.012,
                      "fulfillmentThroughput": 355,
                      "dltBacklogCount": 0,
                      "oldestDltEventAt": null,
                      "outboxBacklogCount": 2,
                      "oldestOutboxEventAt": "2026-07-20T23:58:01.442Z",
                      "recoverySuccessRate": 0.8,
                      "manualTouchRate": 0.019
                    }
                    """)))
  @GetMapping("/kpis/overview")
  public KpiOverviewResponse overview(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
    validateRange(from, to);
    return overviewService.overview(from, to);
  }

  @Operation(summary = "Orders received/completed/cancelled, bucketed by day or hour")
  @GetMapping("/kpis/timeseries")
  public KpiTimeSeriesResponse timeSeries(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      @RequestParam(defaultValue = "DAY") TimeSeriesInterval interval) {
    validateRange(from, to);
    return timeSeriesService.timeSeries(from, to, interval);
  }

  @Operation(summary = "Stage duration percentiles and end-to-end cycle time percentiles")
  @GetMapping("/kpis/stage-durations")
  public StageDurationKpiResponse stageDurations(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
    validateRange(from, to);
    return stageDurationKpiService.stageDurations(from, to);
  }

  @Operation(summary = "Current open backlog by stage, with SLA breach count/rate")
  @GetMapping("/backlog")
  public BacklogResponse backlog() {
    return stageDurationKpiService.backlog();
  }

  @Operation(summary = "Currently stuck orders, bucketed by age")
  @GetMapping("/kpis/stuck-orders")
  public StuckOrdersResponse stuckOrders() {
    return stageDurationKpiService.stuckOrders();
  }

  @Operation(summary = "Every SKU currently below its low-stock threshold")
  @GetMapping("/low-stock")
  public List<LowStockSignalResponse> lowStock() {
    return lowStockSignalRepository.findByBelowThresholdTrueOrderBySku().stream()
        .map(OperationsKpiController::toResponse)
        .toList();
  }

  private static void validateRange(Instant from, Instant to) {
    if (from.isAfter(to)) {
      throw new InvalidDateRangeException(from, to);
    }
  }

  private static LowStockSignalResponse toResponse(LowStockSignal signal) {
    return new LowStockSignalResponse(
        signal.getSku(),
        signal.getAvailableQuantity(),
        signal.getThreshold(),
        signal.isBelowThreshold(),
        signal.getOccurredAt());
  }
}
