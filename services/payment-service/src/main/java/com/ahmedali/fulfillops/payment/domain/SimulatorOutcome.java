package com.ahmedali.fulfillops.payment.domain;

/** The closed set of outcomes a simulator_rules row can declare. See V2__payments.sql. */
public enum SimulatorOutcome {
  APPROVE,
  DECLINE_INSUFFICIENT_FUNDS,
  DECLINE_CARD_DECLINED,
  TIMEOUT,
  TEMPORARY_ERROR
}
