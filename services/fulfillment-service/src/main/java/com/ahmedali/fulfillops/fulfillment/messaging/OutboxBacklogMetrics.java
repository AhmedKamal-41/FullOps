package com.ahmedali.fulfillops.fulfillment.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls outbox_event on a slow schedule to expose backlog size and oldest-unpublished age as gauges
 * — kept separate from OutboxRelay's own publish loop (which runs every few hundred milliseconds
 * and has no reason to also do this bookkeeping every cycle).
 */
@Component
public class OutboxBacklogMetrics {

  private final OutboxEventRepository outboxEventRepository;
  private final AtomicLong backlogCount = new AtomicLong();
  private final AtomicLong oldestUnpublishedAgeSeconds = new AtomicLong();

  public OutboxBacklogMetrics(
      OutboxEventRepository outboxEventRepository, MeterRegistry meterRegistry) {
    this.outboxEventRepository = outboxEventRepository;
    Gauge.builder("outbox.backlog.count", backlogCount, AtomicLong::get)
        .description("Outbox rows not yet published")
        .register(meterRegistry);
    Gauge.builder(
            "outbox.oldest.unpublished.age.seconds", oldestUnpublishedAgeSeconds, AtomicLong::get)
        .description(
            "Age in seconds of the oldest unpublished outbox row, 0 when the backlog is empty")
        .register(meterRegistry);
  }

  @Scheduled(fixedDelayString = "${app.messaging.outbox-metrics-interval-ms:15000}")
  public void refresh() {
    backlogCount.set(outboxEventRepository.countByStateNot(OutboxState.PUBLISHED.name()));
    oldestUnpublishedAgeSeconds.set(
        outboxEventRepository
            .findFirstByStateNotOrderByCreatedAtAsc(OutboxState.PUBLISHED.name())
            .map(event -> Duration.between(event.getOccurredAt(), Instant.now()).getSeconds())
            .orElse(0L));
  }
}
