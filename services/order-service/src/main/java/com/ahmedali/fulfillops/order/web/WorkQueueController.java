package com.ahmedali.fulfillops.order.web;

import com.ahmedali.fulfillops.order.domain.OrderOperationsProjection;
import com.ahmedali.fulfillops.order.domain.OrderStatus;
import com.ahmedali.fulfillops.order.service.OrderTimelineService;
import com.ahmedali.fulfillops.order.service.WorkQueueFilter;
import com.ahmedali.fulfillops.order.service.WorkQueueService;
import com.ahmedali.fulfillops.order.web.dto.MoneyDto;
import com.ahmedali.fulfillops.order.web.dto.OrderTimelineResponse;
import com.ahmedali.fulfillops.order.web.dto.WorkQueueItemResponse;
import io.swagger.v3.oas.annotations.Operation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The exception work queue: search/filter/paginate open orders, export the current filter as CSV,
 * and view one order's full event timeline. OPERATOR/ADMIN only (see SecurityConfig). Page size is
 * bounded by spring.data.web.pageable.max-page-size (application.yml) — Spring Boot's own built-in
 * property, not a hand-rolled cap.
 */
@RestController
@RequestMapping("/api/v1/ops")
public class WorkQueueController {

  private final WorkQueueService workQueueService;
  private final OrderTimelineService timelineService;
  private final WorkQueueCsvWriter csvWriter;

  public WorkQueueController(
      WorkQueueService workQueueService,
      OrderTimelineService timelineService,
      WorkQueueCsvWriter csvWriter) {
    this.workQueueService = workQueueService;
    this.timelineService = timelineService;
    this.csvWriter = csvWriter;
  }

  @Operation(summary = "Search the work queue by status, customer, SLA-breach, and stuck")
  @GetMapping("/work-queue")
  public Page<WorkQueueItemResponse> workQueue(
      @RequestParam(required = false) OrderStatus status,
      @RequestParam(required = false) UUID customerId,
      @RequestParam(required = false) Boolean slaBreached,
      @RequestParam(required = false) Boolean stuck,
      Pageable pageable) {
    WorkQueueFilter filter = new WorkQueueFilter(status, customerId, slaBreached, stuck);
    return workQueueService.search(filter, pageable).map(WorkQueueController::toResponse);
  }

  @Operation(summary = "Export the currently filtered work queue as CSV")
  @GetMapping(value = "/work-queue/export", produces = "text/csv")
  public ResponseEntity<StreamingResponseBody> exportWorkQueue(
      @RequestParam(required = false) OrderStatus status,
      @RequestParam(required = false) UUID customerId,
      @RequestParam(required = false) Boolean slaBreached,
      @RequestParam(required = false) Boolean stuck) {
    WorkQueueFilter filter = new WorkQueueFilter(status, customerId, slaBreached, stuck);
    var rows = workQueueService.searchAll(filter, Sort.by("createdAt").descending());
    StreamingResponseBody body =
        outputStream -> {
          try (var writer = new java.io.OutputStreamWriter(outputStream)) {
            csvWriter.write(writer, rows);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        };
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"work-queue.csv\"")
        .contentType(MediaType.parseMediaType("text/csv"))
        .body(body);
  }

  @Operation(summary = "One order's full status-change and incident-action timeline")
  @GetMapping("/orders/{orderId}/timeline")
  public OrderTimelineResponse timeline(@PathVariable UUID orderId) {
    return timelineService.timelineFor(orderId);
  }

  private static WorkQueueItemResponse toResponse(OrderOperationsProjection projection) {
    return new WorkQueueItemResponse(
        projection.getOrderId(),
        projection.getCustomerId(),
        projection.getStatus().name(),
        new MoneyDto(projection.getCurrencyCode(), projection.getTotalAmount().toPlainString()),
        projection.getCreatedAt(),
        projection.getCurrentStageEnteredAt(),
        projection.getUpdatedAt(),
        projection.getInventoryRejectionReasonCode(),
        projection.getPaymentDeclineReasonCode(),
        projection.getPaymentTechnicalFailureCount(),
        projection.getCancellationReasonCode(),
        projection.getRequiresReviewReasonCode(),
        projection.getOpenIncidentCount());
  }
}
