package com.smartdelivery.product.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Stable public shape for a page of products -- deliberately not Spring Data's
 * {@code Page} interface directly, so the API contract doesn't change if the
 * pagination implementation ever does.
 */
public record ProductPageResponse(
        List<ProductResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static ProductPageResponse from(Page<ProductResponse> page) {
        return new ProductPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
