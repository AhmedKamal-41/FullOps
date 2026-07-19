package com.ahmedali.fulfillops.inventory.web;

import com.ahmedali.fulfillops.inventory.service.ProductService;
import com.ahmedali.fulfillops.inventory.web.dto.CreateProductRequest;
import com.ahmedali.fulfillops.inventory.web.dto.ProductResponse;
import com.ahmedali.fulfillops.inventory.web.dto.UpdateProductRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
@Validated
public class ProductController {

  private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

  private final ProductService productService;

  public ProductController(ProductService productService) {
    this.productService = productService;
  }

  @PostMapping
  public ResponseEntity<ProductResponse> createProduct(
      @RequestHeader(IDEMPOTENCY_KEY_HEADER) @NotBlank @Size(max = 255) String idempotencyKey,
      @Valid @RequestBody CreateProductRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    ProductResponse product =
        productService.createProduct(jwt.getSubject(), idempotencyKey, request);
    return ResponseEntity.created(URI.create("/api/v1/products/" + product.sku())).body(product);
  }

  @PutMapping("/{sku}")
  public ProductResponse updateProduct(
      @PathVariable String sku, @Valid @RequestBody UpdateProductRequest request) {
    return productService.updateProduct(sku, request);
  }
}
