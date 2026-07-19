package com.ahmedali.fulfillops.payment.provider;

/** The simulated provider reported a transient internal error. Retryable. */
public class ProviderTemporaryErrorException extends ProviderUnavailableException {

  public ProviderTemporaryErrorException(String message) {
    super(message);
  }
}
