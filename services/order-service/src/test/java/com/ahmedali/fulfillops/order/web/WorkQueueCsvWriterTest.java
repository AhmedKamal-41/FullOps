package com.ahmedali.fulfillops.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ahmedali.fulfillops.order.domain.OrderOperationsProjection;
import com.ahmedali.fulfillops.order.domain.OrderStatus;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkQueueCsvWriterTest {

  private final WorkQueueCsvWriter writer = new WorkQueueCsvWriter();

  @Test
  void writesAHeaderRowAndOneRowPerOrder() throws Exception {
    OrderOperationsProjection projection =
        new OrderOperationsProjection(
            UUID.randomUUID(),
            UUID.randomUUID(),
            OrderStatus.PENDING,
            "USD",
            new BigDecimal("10.00"),
            Instant.parse("2026-01-01T00:00:00Z"));

    StringWriter out = new StringWriter();
    writer.write(out, List.of(projection));

    String[] lines = out.toString().split("\r\n");
    assertThat(lines).hasSize(2);
    assertThat(lines[0]).startsWith("orderId,customerId,status");
    assertThat(lines[1]).contains("PENDING").contains("USD").contains("10.00");
  }

  @Test
  void reasonDetailContainingACommaAQuoteAndANewlineIsProperlyEscaped() throws Exception {
    OrderOperationsProjection projection =
        new OrderOperationsProjection(
            UUID.randomUUID(),
            UUID.randomUUID(),
            OrderStatus.CANCELLED,
            "USD",
            new BigDecimal("10.00"),
            Instant.parse("2026-01-01T00:00:00Z"));
    projection.recordInventoryRejection("reason, with \"quotes\"\nand a newline");

    StringWriter out = new StringWriter();
    writer.write(out, List.of(projection));

    // RFC 4180: the whole field is quoted, and an embedded quote is doubled.
    assertThat(out.toString()).contains("\"reason, with \"\"quotes\"\"\nand a newline\"");
  }

  @Test
  void emptyRowsProducesOnlyTheHeader() throws Exception {
    StringWriter out = new StringWriter();
    writer.write(out, List.of());

    assertThat(out.toString().strip())
        .isEqualTo(
            "orderId,customerId,status,currencyCode,totalAmount,createdAt,currentStageEnteredAt,updatedAt,inventoryRejectionReasonCode,paymentDeclineReasonCode,paymentTechnicalFailureCount,cancellationReasonCode,requiresReviewReasonCode,openIncidentCount");
  }
}
