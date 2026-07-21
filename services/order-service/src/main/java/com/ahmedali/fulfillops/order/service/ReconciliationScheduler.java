package com.ahmedali.fulfillops.order.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Fires ReconciliationService on a fixed interval — see application.yml for the schedule. */
@Component
public class ReconciliationScheduler {

  private final ReconciliationService reconciliationService;

  public ReconciliationScheduler(ReconciliationService reconciliationService) {
    this.reconciliationService = reconciliationService;
  }

  @Scheduled(fixedDelayString = "${app.reconciliation.interval-ms}")
  public void run() {
    reconciliationService.reconcile();
  }
}
