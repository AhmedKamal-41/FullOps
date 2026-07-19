package com.ahmedali.fulfillops.payment.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 hex digest of a canonical request string. Used by RefundService to detect an
 * Idempotency-Key reused with a materially different request body, the same technique order-service
 * and inventory-service use.
 */
final class RequestFingerprint {

  private RequestFingerprint() {}

  static String sha256Hex(String canonical) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(
          "SHA-256 is a JDK-mandated algorithm and must always be available", e);
    }
  }
}
