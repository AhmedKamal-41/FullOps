package com.ahmedali.fulfillops.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ahmedali.fulfillops.inventory.messaging.OutboxEventWriter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The threshold in every test here is 10 — matches app.inventory.low-stock.default-threshold. */
class LowStockSignalEvaluatorTest {

  private static final int THRESHOLD = 10;

  private final OutboxEventWriter outboxEventWriter = mock(OutboxEventWriter.class);
  private final LowStockSignalEvaluator evaluator =
      new LowStockSignalEvaluator(outboxEventWriter, THRESHOLD);

  private final UUID correlationId = UUID.randomUUID();
  private final UUID causationId = UUID.randomUUID();

  @Test
  void droppingFromAboveToAtOrBelowTheThresholdEmitsABelowThresholdEvent() {
    evaluator.evaluate("WIDGET-BLUE-M", 12, 10, correlationId, causationId);

    verify(outboxEventWriter)
        .write(eq("InventoryLowStock"), eq(1), any(), eq(correlationId), eq(causationId), any());
  }

  @Test
  void recoveringFromAtOrBelowToAboveTheThresholdEmitsARecoveredEvent() {
    evaluator.evaluate("WIDGET-BLUE-M", 10, 11, correlationId, causationId);

    verify(outboxEventWriter)
        .write(eq("InventoryLowStock"), eq(1), any(), eq(correlationId), eq(causationId), any());
  }

  @Test
  void stayingAboveTheThresholdEmitsNothing() {
    evaluator.evaluate("WIDGET-BLUE-M", 20, 15, correlationId, causationId);

    verify(outboxEventWriter, never()).write(any(), any(Integer.class), any(), any(), any(), any());
  }

  @Test
  void stayingAtOrBelowTheThresholdEmitsNothing() {
    evaluator.evaluate("WIDGET-BLUE-M", 5, 2, correlationId, causationId);

    verify(outboxEventWriter, never()).write(any(), any(Integer.class), any(), any(), any(), any());
  }

  @Test
  void theSameSkuAlwaysProducesTheSameAggregateIdAcrossCrossings() {
    evaluator.evaluate("WIDGET-BLUE-M", 12, 8, correlationId, causationId); // crosses down
    evaluator.evaluate("WIDGET-BLUE-M", 5, 15, correlationId, causationId); // crosses up

    ArgumentCaptor<UUID> aggregateIdCaptor = ArgumentCaptor.forClass(UUID.class);
    verify(outboxEventWriter, times(2))
        .write(any(), any(Integer.class), aggregateIdCaptor.capture(), any(), any(), any());
    assertThat(aggregateIdCaptor.getAllValues())
        .containsOnly(aggregateIdCaptor.getAllValues().get(0));
  }
}
