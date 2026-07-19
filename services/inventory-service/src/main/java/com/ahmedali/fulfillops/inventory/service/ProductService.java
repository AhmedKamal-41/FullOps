package com.ahmedali.fulfillops.inventory.service;

import com.ahmedali.fulfillops.inventory.domain.IdempotencyRequest;
import com.ahmedali.fulfillops.inventory.domain.IdempotencyRequestId;
import com.ahmedali.fulfillops.inventory.domain.IdempotencyRequestRepository;
import com.ahmedali.fulfillops.inventory.domain.Product;
import com.ahmedali.fulfillops.inventory.domain.ProductRepository;
import com.ahmedali.fulfillops.inventory.web.dto.CreateProductRequest;
import com.ahmedali.fulfillops.inventory.web.dto.ProductResponse;
import com.ahmedali.fulfillops.inventory.web.dto.UpdateProductRequest;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates product creation and updates. Deliberately not @Transactional itself — see
 * ProductCreationTransaction for why the actual insert lives in its own bean with its own
 * transaction boundary, which is what lets createProduct catch a lost idempotency-key race and
 * recover from it instead of the whole request failing.
 */
@Service
public class ProductService {

  private final ProductRepository productRepository;
  private final IdempotencyRequestRepository idempotencyRequestRepository;
  private final ProductCreationTransaction productCreationTransaction;

  public ProductService(
      ProductRepository productRepository,
      IdempotencyRequestRepository idempotencyRequestRepository,
      ProductCreationTransaction productCreationTransaction) {
    this.productRepository = productRepository;
    this.idempotencyRequestRepository = idempotencyRequestRepository;
    this.productCreationTransaction = productCreationTransaction;
  }

  public ProductResponse createProduct(
      String actorId, String idempotencyKey, CreateProductRequest request) {
    String fingerprint = fingerprint(actorId, request);
    IdempotencyRequestId idempotencyRequestId = new IdempotencyRequestId(actorId, idempotencyKey);

    Optional<IdempotencyRequest> existing =
        idempotencyRequestRepository.findById(idempotencyRequestId);
    if (existing.isPresent()) {
      return replayOrConflict(existing.get(), idempotencyKey, fingerprint);
    }

    try {
      Product product =
          productCreationTransaction.createNewProduct(
              actorId,
              idempotencyKey,
              request.sku(),
              request.name(),
              request.description(),
              fingerprint);
      return toResponse(product);
    } catch (DataIntegrityViolationException lostRace) {
      Optional<IdempotencyRequest> winner =
          idempotencyRequestRepository.findById(idempotencyRequestId);
      if (winner.isPresent()) {
        return replayOrConflict(winner.get(), idempotencyKey, fingerprint);
      }
      // Our own idempotency key never made it in, so the failure wasn't a key replay race — it
      // was the sku UNIQUE constraint, tripped by a completely different request.
      throw new ProductAlreadyExistsException(request.sku(), lostRace);
    }
  }

  public ProductResponse updateProduct(String sku, UpdateProductRequest request) {
    Product product =
        productRepository.findBySku(sku).orElseThrow(() -> new ProductNotFoundException(sku));
    product.update(request.name(), request.description());
    productRepository.save(product);
    return toResponse(product);
  }

  private ProductResponse replayOrConflict(
      IdempotencyRequest existing, String idempotencyKey, String fingerprint) {
    if (!existing.getRequestFingerprint().equals(fingerprint)) {
      throw new IdempotencyKeyConflictException(idempotencyKey);
    }
    Product product =
        productRepository
            .findById(existing.getReferenceId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "idempotency_requests row references a missing product: "
                            + existing.getReferenceId()));
    return toResponse(product);
  }

  private static String fingerprint(String actorId, CreateProductRequest request) {
    String canonical =
        actorId + '|' + request.sku() + '|' + request.name() + '|' + request.description();
    return RequestFingerprint.sha256Hex(canonical);
  }

  private static ProductResponse toResponse(Product product) {
    return new ProductResponse(
        product.getProductId(),
        product.getSku(),
        product.getName(),
        product.getDescription(),
        product.getCreatedAt());
  }
}
