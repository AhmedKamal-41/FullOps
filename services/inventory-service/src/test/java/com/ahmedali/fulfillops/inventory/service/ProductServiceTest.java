package com.ahmedali.fulfillops.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ahmedali.fulfillops.inventory.domain.IdempotencyRequest;
import com.ahmedali.fulfillops.inventory.domain.IdempotencyRequestRepository;
import com.ahmedali.fulfillops.inventory.domain.Product;
import com.ahmedali.fulfillops.inventory.domain.ProductRepository;
import com.ahmedali.fulfillops.inventory.domain.ReferenceType;
import com.ahmedali.fulfillops.inventory.web.dto.CreateProductRequest;
import com.ahmedali.fulfillops.inventory.web.dto.ProductResponse;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class ProductServiceTest {

  private final ProductRepository productRepository = mock(ProductRepository.class);
  private final IdempotencyRequestRepository idempotencyRequestRepository =
      mock(IdempotencyRequestRepository.class);
  private final ProductCreationTransaction productCreationTransaction =
      mock(ProductCreationTransaction.class);

  private ProductService productService;

  private final String actorId = UUID.randomUUID().toString();
  private final String idempotencyKey = "create-widget-1";

  @BeforeEach
  void setUp() {
    productService =
        new ProductService(
            productRepository, idempotencyRequestRepository, productCreationTransaction);
  }

  @Test
  void newIdempotencyKeyCreatesAProductThroughTheTransactionBean() {
    when(idempotencyRequestRepository.findById(any())).thenReturn(Optional.empty());
    Product created = aProduct();
    when(productCreationTransaction.createNewProduct(
            eq(actorId), eq(idempotencyKey), any(), any(), any(), any()))
        .thenReturn(created);

    ProductResponse response = productService.createProduct(actorId, idempotencyKey, aRequest());

    assertThat(response.sku()).isEqualTo(created.getSku());
  }

  @Test
  void replayingTheSameKeyAndPayloadReturnsTheOriginalProductWithoutCreatingAnother() {
    Product original = aProduct();
    IdempotencyRequest existingRow = existingRowFor(original, aRequest());
    when(idempotencyRequestRepository.findById(any())).thenReturn(Optional.of(existingRow));
    when(productRepository.findById(original.getProductId())).thenReturn(Optional.of(original));

    ProductResponse response = productService.createProduct(actorId, idempotencyKey, aRequest());

    assertThat(response.productId()).isEqualTo(original.getProductId());
    verify(productCreationTransaction, never())
        .createNewProduct(any(), any(), any(), any(), any(), any());
  }

  @Test
  void reusingTheKeyWithADifferentPayloadIsRejectedAsAConflict() {
    Product original = aProduct();
    IdempotencyRequest existingRow = existingRowFor(original, aRequest());
    when(idempotencyRequestRepository.findById(any())).thenReturn(Optional.of(existingRow));

    CreateProductRequest differentRequest =
        new CreateProductRequest("WIDGET-BLUE-M", "A completely different name", null);

    assertThatThrownBy(
            () -> productService.createProduct(actorId, idempotencyKey, differentRequest))
        .isInstanceOf(IdempotencyKeyConflictException.class);
  }

  @Test
  void losingTheCreationRaceReconcilesFromTheRowThatWon() {
    Product winner = aProduct();
    IdempotencyRequest winnerRow = existingRowFor(winner, aRequest());
    when(idempotencyRequestRepository.findById(any()))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(winnerRow));
    when(productCreationTransaction.createNewProduct(any(), any(), any(), any(), any(), any()))
        .thenThrow(new DataIntegrityViolationException("duplicate key"));
    when(productRepository.findById(winner.getProductId())).thenReturn(Optional.of(winner));

    ProductResponse response = productService.createProduct(actorId, idempotencyKey, aRequest());

    assertThat(response.productId()).isEqualTo(winner.getProductId());
  }

  @Test
  void aSkuCollisionFromACompletelyDifferentRequestIsReportedAsAlreadyExists() {
    when(idempotencyRequestRepository.findById(any())).thenReturn(Optional.empty());
    when(productCreationTransaction.createNewProduct(any(), any(), any(), any(), any(), any()))
        .thenThrow(new DataIntegrityViolationException("duplicate key"));

    assertThatThrownBy(() -> productService.createProduct(actorId, idempotencyKey, aRequest()))
        .isInstanceOf(ProductAlreadyExistsException.class);
  }

  private CreateProductRequest aRequest() {
    return new CreateProductRequest("WIDGET-BLUE-M", "Blue Widget", "A fictional blue widget");
  }

  private Product aProduct() {
    return new Product(
        UUID.randomUUID(), "WIDGET-BLUE-M", "Blue Widget", "A fictional blue widget");
  }

  private IdempotencyRequest existingRowFor(Product product, CreateProductRequest request) {
    String fingerprint =
        RequestFingerprint.sha256Hex(
            actorId + '|' + request.sku() + '|' + request.name() + '|' + request.description());
    return new IdempotencyRequest(
        actorId, idempotencyKey, fingerprint, ReferenceType.PRODUCT, product.getProductId());
  }
}
