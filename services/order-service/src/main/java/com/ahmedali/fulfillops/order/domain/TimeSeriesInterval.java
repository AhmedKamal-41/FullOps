package com.ahmedali.fulfillops.order.domain;

/**
 * The only two bucket widths the time-series KPI endpoint supports — matches a Postgres date_trunc
 * field.
 */
public enum TimeSeriesInterval {
  HOUR,
  DAY;

  public String toDateTruncField() {
    return name().toLowerCase();
  }
}
