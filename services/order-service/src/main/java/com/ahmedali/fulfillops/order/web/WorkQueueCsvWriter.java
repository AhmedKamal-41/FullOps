package com.ahmedali.fulfillops.order.web;

import com.ahmedali.fulfillops.order.domain.OrderOperationsProjection;
import java.io.IOException;
import java.io.Writer;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;

/**
 * The currently-filtered work queue, as CSV — same rows WorkQueueService.searchAll returns for a
 * given filter, just serialized differently. Uses commons-csv (RFC 4180: quoting, embedded commas/
 * quotes/newlines) rather than hand-rolled string joining, which is exactly the kind of thing not
 * worth getting subtly wrong.
 */
@Component
public class WorkQueueCsvWriter {

  private static final String[] HEADERS = {
    "orderId",
    "customerId",
    "status",
    "currencyCode",
    "totalAmount",
    "createdAt",
    "currentStageEnteredAt",
    "updatedAt",
    "inventoryRejectionReasonCode",
    "paymentDeclineReasonCode",
    "paymentTechnicalFailureCount",
    "cancellationReasonCode",
    "requiresReviewReasonCode",
    "openIncidentCount"
  };

  public void write(Writer writer, List<OrderOperationsProjection> rows) throws IOException {
    CSVFormat format = CSVFormat.RFC4180.builder().setHeader(HEADERS).get();
    try (CSVPrinter printer = new CSVPrinter(writer, format)) {
      for (OrderOperationsProjection row : rows) {
        printer.printRecord(
            row.getOrderId(),
            row.getCustomerId(),
            row.getStatus(),
            row.getCurrencyCode(),
            row.getTotalAmount(),
            row.getCreatedAt(),
            row.getCurrentStageEnteredAt(),
            row.getUpdatedAt(),
            row.getInventoryRejectionReasonCode(),
            row.getPaymentDeclineReasonCode(),
            row.getPaymentTechnicalFailureCount(),
            row.getCancellationReasonCode(),
            row.getRequiresReviewReasonCode(),
            row.getOpenIncidentCount());
      }
    }
  }
}
