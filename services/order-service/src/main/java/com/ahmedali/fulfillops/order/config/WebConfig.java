package com.ahmedali.fulfillops.order.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode;

/**
 * Without this, a controller returning Page<T> directly (GET /api/v1/orders) serializes Spring
 * Data's internal PageImpl as-is, which Spring Data itself warns is not a stable JSON structure
 * across versions. VIA_DTO wraps it in PagedModel instead, a stable, documented shape.
 */
@Configuration
@EnableSpringDataWebSupport(pageSerializationMode = PageSerializationMode.VIA_DTO)
public class WebConfig {}
