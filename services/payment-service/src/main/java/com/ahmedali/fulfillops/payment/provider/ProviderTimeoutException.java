package com.ahmedali.fulfillops.payment.provider;

/** The simulated provider did not respond in time. Retryable. */
public class ProviderTimeoutException extends ProviderUnavailableException {

  public ProviderTimeoutException(String message) {
    super(message);
  }
}
