package com.ahmedali.fulfillops.order.service;

import java.util.UUID;

public class IncidentNotFoundException extends RuntimeException {

  public IncidentNotFoundException(UUID incidentId) {
    super("no incident found with id " + incidentId);
  }
}
