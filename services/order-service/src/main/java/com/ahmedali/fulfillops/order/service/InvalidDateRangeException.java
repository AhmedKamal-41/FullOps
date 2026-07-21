package com.ahmedali.fulfillops.order.service;

import java.time.Instant;

public class InvalidDateRangeException extends RuntimeException {

  public InvalidDateRangeException(Instant from, Instant to) {
    super("from (" + from + ") must not be after to (" + to + ")");
  }
}
