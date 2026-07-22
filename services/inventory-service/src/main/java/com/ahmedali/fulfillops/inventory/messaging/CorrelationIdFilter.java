package com.ahmedali.fulfillops.inventory.messaging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Every request gets a correlation ID: the caller's X-Correlation-Id if it sent a valid one, or a
 * new one otherwise. It's put in MDC so every log line for this request carries it, echoed back in
 * the response header so a caller can find those logs afterward, and available to controllers (see
 * REQUEST_ATTRIBUTE) to carry into an audit row or outbox event's correlationId — which is why this
 * must always be a UUID, not whatever string a caller happened to send.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String HEADER_NAME = "X-Correlation-Id";
  public static final String MDC_KEY = "correlationId";
  public static final String REQUEST_ATTRIBUTE = "correlationId";

  // Environment label for structured logs (e.g. "local", "test") — same value on every log
  // line this process emits, so it's resolved once at startup rather than per request.
  private static final String ENVIRONMENT_MDC_KEY = "environment";

  private final String environmentLabel;

  public CorrelationIdFilter(Environment environment) {
    String[] activeProfiles = environment.getActiveProfiles();
    this.environmentLabel = activeProfiles.length > 0 ? activeProfiles[0] : "default";
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    UUID correlationId = parseOrGenerate(request.getHeader(HEADER_NAME));

    MDC.put(MDC_KEY, correlationId.toString());
    MDC.put(ENVIRONMENT_MDC_KEY, environmentLabel);
    request.setAttribute(REQUEST_ATTRIBUTE, correlationId);
    response.setHeader(HEADER_NAME, correlationId.toString());
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
      MDC.remove(ENVIRONMENT_MDC_KEY);
    }
  }

  private static UUID parseOrGenerate(String headerValue) {
    if (headerValue == null || headerValue.isBlank()) {
      return UUID.randomUUID();
    }
    try {
      return UUID.fromString(headerValue.trim());
    } catch (IllegalArgumentException notAUuid) {
      return UUID.randomUUID();
    }
  }
}
