package com.ahmedali.fulfillops.inventory.web;

import com.ahmedali.fulfillops.inventory.service.IdempotencyKeyConflictException;
import com.ahmedali.fulfillops.inventory.service.InvalidAdjustmentException;
import com.ahmedali.fulfillops.inventory.service.ProductAlreadyExistsException;
import com.ahmedali.fulfillops.inventory.service.ProductNotFoundException;
import com.ahmedali.fulfillops.inventory.service.StockConcurrencyException;
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
 * missing required header, a malformed body, an @Valid failure on the request body) into RFC 9457
 * Problem Details. This adds the same treatment for this service's own exceptions, plus a generic
 * fallback that never leaks a stack trace.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(InvalidAdjustmentException.class)
  public ProblemDetail handleInvalidAdjustment(InvalidAdjustmentException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(IdempotencyKeyConflictException.class)
  public ProblemDetail handleIdempotencyKeyConflict(IdempotencyKeyConflictException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(ProductAlreadyExistsException.class)
  public ProblemDetail handleProductAlreadyExists(ProductAlreadyExistsException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(ProductNotFoundException.class)
  public ProblemDetail handleProductNotFound(ProductNotFoundException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(StockConcurrencyException.class)
  public ProblemDetail handleStockConcurrency(StockConcurrencyException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  // Constraint violations on simple parameters (e.g. a blank Idempotency-Key header)
  // arrive as this jakarta.validation exception, not the Spring MVC one the base
  // class already handles — that one's only for a validated @RequestBody.
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
