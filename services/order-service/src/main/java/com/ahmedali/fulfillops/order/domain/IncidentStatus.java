package com.ahmedali.fulfillops.order.domain;

/** ACKNOWLEDGED sits between OPEN and RESOLVED — an operator has seen it but not yet closed it. */
public enum IncidentStatus {
  OPEN,
  ACKNOWLEDGED,
  RESOLVED
}
