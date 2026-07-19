package com.ahmedali.fulfillops.inventory.service;

import com.ahmedali.fulfillops.inventory.domain.IdempotencyRequest;
import com.ahmedali.fulfillops.inventory.domain.IdempotencyRequestRepository;
import com.ahmedali.fulfillops.inventory.domain.Product;
import com.ahmedali.fulfillops.inventory.domain.ProductRepository;
import com.ahmedali.fulfillops.inventory.domain.ReferenceType;
import com.ahmedali.fulfillops.inventory.domain.StockLevel;
import com.ahmedali.fulfillops.inventory.domain.StockLevelRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A separate bean (not just a method on ProductService) so its @Transactional boundary is a real
 * Spring-proxy call from the outside — see order-service's OrderCreationTransaction for the same
 * reasoning. Creates the product, its zero-stock StockLevel row, and the idempotency ledger row
 * together; saveAndFlush on the last line forces the idempotency unique-constraint check to happen
 * here, synchronously, so ProductService can catch a lost race.
 */
@Component
public class ProductCreationTransaction {

  private final ProductRepository productRepository;
  private final StockLevelRepository stockLevelRepository;
  private final IdempotencyRequestRepository idempotencyRequestRepository;

  public ProductCreationTransaction(
      ProductRepository productRepository,
      StockLevelRepository stockLevelRepository,
      IdempotencyRequestRepository idempotencyRequestRepository) {
    this.productRepository = productRepository;
    this.stockLevelRepository = stockLevelRepository;
    this.idempotencyRequestRepository = idempotencyRequestRepository;
  }

  @Transactional
  public Product createNewProduct(
      String actorId,
      String idempotencyKey,
      String sku,
      String name,
      String description,
      String requestFingerprint) {
    UUID productId = UUID.randomUUID();
    Product product = new Product(productId, sku, name, description);
    productRepository.save(product);

    stockLevelRepository.save(new StockLevel(UUID.randomUUID(), sku));

    idempotencyRequestRepository.saveAndFlush(
        new IdempotencyRequest(
            actorId, idempotencyKey, requestFingerprint, ReferenceType.PRODUCT, productId));

    return product;
  }
}
