package com.ahmedali.fulfillops.order.service;

import java.util.UUID;

public class IncidentAlreadyResolvedException extends RuntimeException {

  public IncidentAlreadyResolvedException(UUID incidentId) {
    super("incident " + incidentId + " is already resolved");
  }
}
