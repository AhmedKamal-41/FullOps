package com.ahmedali.fulfillops.order.service;

import com.ahmedali.fulfillops.order.domain.OrderStatus;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Demonstration-default SLA thresholds (see application.yml's app.ops.sla.* comment) — never
 * claimed as measured targets. stageThresholds keys are OrderStatus names; a stage with no
 * configured threshold is simply never flagged as breached. ageBuckets defines the boundaries the
 * stuck-orders KPI groups orders into (e.g. "0-1h", "1-4h", ...).
 */
@Component
@ConfigurationProperties(prefix = "app.ops.sla")
public class OpsSlaProperties {

  private Map<String, Duration> stageThresholds = new LinkedHashMap<>();
  private List<Duration> ageBuckets = List.of();

  public Map<String, Duration> getStageThresholds() {
    return stageThresholds;
  }

  public void setStageThresholds(Map<String, Duration> stageThresholds) {
    this.stageThresholds = stageThresholds;
  }

  public List<Duration> getAgeBuckets() {
    return ageBuckets;
  }

  public void setAgeBuckets(List<Duration> ageBuckets) {
    this.ageBuckets = ageBuckets;
  }

  public Duration thresholdFor(OrderStatus stage) {
    return stageThresholds.get(stage.name());
  }
}
