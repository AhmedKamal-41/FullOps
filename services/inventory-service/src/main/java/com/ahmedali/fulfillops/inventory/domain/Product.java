package com.ahmedali.fulfillops.inventory.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A fictional catalog product. Creating one also creates its zero-stock StockLevel row. */
@Entity
@Table(name = "product")
public class Product {

  @Id private UUID productId;

  private String sku;
  private String name;
  private String description;
  private Instant createdAt;
  private Instant updatedAt;

  protected Product() {
    // JPA
  }

  public Product(UUID productId, String sku, String name, String description) {
    this.productId = productId;
    this.sku = sku;
    this.name = name;
    this.description = description;
    this.createdAt = Instant.now();
    this.updatedAt = Instant.now();
  }

  public void update(String name, String description) {
    this.name = name;
    this.description = description;
    this.updatedAt = Instant.now();
  }

  public UUID getProductId() {
    return productId;
  }

  public String getSku() {
    return sku;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
