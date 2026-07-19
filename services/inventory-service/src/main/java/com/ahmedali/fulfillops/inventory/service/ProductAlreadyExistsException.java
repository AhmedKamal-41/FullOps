package com.ahmedali.fulfillops.inventory.service;

/**
 * Thrown when a product create fails on the sku UNIQUE constraint for a reason unrelated to
 * idempotency-key replay — i.e. a different Idempotency-Key was used for the same sku. See
 * ProductService.createProduct for how this is told apart from a lost idempotency-key race.
 */
public class ProductAlreadyExistsException extends RuntimeException {

  public ProductAlreadyExistsException(String sku, Throwable cause) {
    super("a product with sku " + sku + " already exists", cause);
  }
}
