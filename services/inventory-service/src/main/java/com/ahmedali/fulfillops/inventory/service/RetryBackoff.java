package com.ahmedali.fulfillops.inventory.service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * A short, jittered pause between bounded in-process retries of an optimistic-lock conflict.
 * Without jitter, threads that lose a version race at the same instant tend to retry at the same
 * instant too, colliding again — under heavy contention (many concurrent reservations for one SKU)
 * that can starve a single unlucky thread out of its whole retry budget. The pause grows with the
 * attempt number, capped, so early retries stay fast and only sustained contention slows down.
 */
final class RetryBackoff {

  private static final long BASE_DELAY_MILLIS = 10;
  private static final long MAX_DELAY_MILLIS = 200;

  private RetryBackoff() {}

  static void pauseBeforeRetry(int attemptJustFailed) {
    long delayCeiling = Math.min(BASE_DELAY_MILLIS * attemptJustFailed, MAX_DELAY_MILLIS);
    long delay = ThreadLocalRandom.current().nextLong(delayCeiling + 1);
    try {
      Thread.sleep(delay);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
