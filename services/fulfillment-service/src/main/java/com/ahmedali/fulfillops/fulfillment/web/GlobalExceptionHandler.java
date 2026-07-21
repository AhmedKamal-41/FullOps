package com.ahmedali.fulfillops.fulfillment.web;

import com.ahmedali.fulfillops.fulfillment.domain.FulfillmentAlreadyClaimedException;
import com.ahmedali.fulfillops.fulfillment.service.DeadLetterEventAlreadyReplayedException;
import com.ahmedali.fulfillops.fulfillment.service.DeadLetterEventNotFoundException;
import com.ahmedali.fulfillops.fulfillment.service.FulfillmentCancellationNotAllowedException;
import com.ahmedali.fulfillops.fulfillment.service.FulfillmentNotFoundException;
import com.ahmedali.fulfillops.fulfillment.service.FulfillmentVersionConflictException;
import com.ahmedali.fulfillops.fulfillment.service.InvalidFulfillmentRequestException;
import com.ahmedali.fulfillops.fulfillment.service.InvalidFulfillmentTransitionException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Extends ResponseEntityExceptionHandler, which already turns Spring MVC's own exceptions (a
 * missing required header, a malformed body, an @Valid failure) into RFC 9457 Problem Details. This
 * adds the same treatment for this service's own exceptions, plus a generic fallback that never
 * leaks a stack trace.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(FulfillmentNotFoundException.class)
  public ProblemDetail handleNotFound(FulfillmentNotFoundException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(InvalidFulfillmentRequestException.class)
  public ProblemDetail handleInvalidRequest(InvalidFulfillmentRequestException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(InvalidFulfillmentTransitionException.class)
  public ProblemDetail handleInvalidTransition(InvalidFulfillmentTransitionException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(FulfillmentAlreadyClaimedException.class)
  public ProblemDetail handleAlreadyClaimed(FulfillmentAlreadyClaimedException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(FulfillmentVersionConflictException.class)
  public ProblemDetail handleVersionConflict(FulfillmentVersionConflictException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(DeadLetterEventNotFoundException.class)
  public ProblemDetail handleDeadLetterEventNotFound(DeadLetterEventNotFoundException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(DeadLetterEventAlreadyReplayedException.class)
  public ProblemDetail handleDeadLetterEventAlreadyReplayed(
      DeadLetterEventAlreadyReplayedException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(FulfillmentCancellationNotAllowedException.class)
  public ProblemDetail handleCancellationNotAllowed(FulfillmentCancellationNotAllowedException e) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    problem.setProperty("reasonCode", e.getReasonCode());
    return problem;
  }

  // Constraint violations on simple parameters (e.g. a missing If-Match header) arrive as this
  // jakarta.validation exception, not the Spring MVC one the base class already handles — that
  // one's only for a validated @RequestBody.
  @ExceptionHandler(ConstraintViolationException.class)
  public ProblemDetail handleConstraintViolation(ConstraintViolationException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected(Exception e, HttpServletRequest request) {
    log.error("unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), e);
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred. Please try again.");
  }
}
