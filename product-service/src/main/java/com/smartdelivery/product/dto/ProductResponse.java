package com.smartdelivery.product.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Serializable: this DTO (not the JPA entity) is what gets stored in the Redis
 * product cache -- see CacheConfig / ProductService.
 */
public record ProductResponse(
        UUID id,
        String sku,
        String name,
        String description,
        BigDecimal price,
        String imageUrl,
        boolean active,
        UUID categoryId,
        String categoryName,
        Instant createdAt,
        Instant updatedAt
) implements Serializable {
}
