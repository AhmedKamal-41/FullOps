package com.ahmedali.fulfillops.payment.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer outcome counters shared by every listener in this service. Tags are always a
 * fixed, small set of values (event type names, exception class names) — never an event ID, order
 * ID, or raw exception message — so cardinality stays bounded no matter how much traffic flows
 * through.
 */
@Component
public class KafkaListenerMetrics {

  private final MeterRegistry meterRegistry;

  public KafkaListenerMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  public void recordDuplicate(String eventType) {
    counter("kafka.consumer.duplicate", eventType).increment();
  }

  public void recordProcessingFailure(String eventType, String errorClass) {
    Counter.builder("kafka.consumer.processing.failures")
        .tag("eventType", eventType)
        .tag("errorClass", errorClass)
        .register(meterRegistry)
        .increment();
  }

  public void recordDeadLettered(String eventType) {
    counter("kafka.consumer.dlt", eventType).increment();
  }

  private Counter counter(String name, String eventType) {
    return Counter.builder(name).tag("eventType", eventType).register(meterRegistry);
  }
}
