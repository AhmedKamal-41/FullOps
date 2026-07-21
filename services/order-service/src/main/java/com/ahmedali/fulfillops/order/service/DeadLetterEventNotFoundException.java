package com.ahmedali.fulfillops.order.service;

import java.util.UUID;

public class DeadLetterEventNotFoundException extends RuntimeException {

  public DeadLetterEventNotFoundException(UUID eventId) {
    super("no dead-lettered event found with id " + eventId);
  }
}
