package com.ahmedali.fulfillops.payment.messaging;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The wire shape contracts/events/Money.v1.schema.json defines: amount as a decimal string with
 * exactly 2 fractional digits, not a JSON number, so it round-trips exactly instead of risking
 * float/double precision loss on the receiving end. RoundingMode.UNNECESSARY deliberately throws
 * rather than silently rounding if a caller ever passes an amount with more than 2 decimal places —
 * that would be a bug upstream, not something to paper over here.
 */
public record MoneyPayload(String currencyCode, String amount) {

  public static MoneyPayload of(String currencyCode, BigDecimal amount) {
    return new MoneyPayload(
        currencyCode, amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString());
  }
}
