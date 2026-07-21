package com.ahmedali.fulfillops.inventory.service;

import java.util.UUID;

public class DeadLetterEventAlreadyReplayedException extends RuntimeException {

  public DeadLetterEventAlreadyReplayedException(UUID eventId) {
    super("dead-lettered event " + eventId + " was already replayed");
  }
}
