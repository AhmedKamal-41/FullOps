package com.ahmedali.fulfillops.payment.provider;

/**
 * A provider call's business result. Both cases are ordinary, expected outcomes — not exceptions —
 * because neither is a technical failure and neither should ever be retried; a technical failure is
 * instead thrown as a ProviderUnavailableException subtype.
 */
public sealed interface ProviderAuthorizationOutcome {

  record Approved() implements ProviderAuthorizationOutcome {}

  record Declined(String reasonCode, String reasonDetail) implements ProviderAuthorizationOutcome {}
}
