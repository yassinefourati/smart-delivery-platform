package com.smartdelivery.order.client;

import com.smartdelivery.order.exception.ProductServiceUnavailableException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;
import java.util.UUID;

/**
 * Synchronous REST call to product-service to snapshot a product's current
 * name/price/availability at order-creation time (docs/database-design.md). This is
 * deliberately not a Kafka call -- order creation needs the answer before it can build
 * the order at all, unlike the saga steps (reserve inventory, charge payment) that
 * follow order creation and are event-driven (docs/saga.md, wired in Phase 7).
 *
 * No retry/circuit-breaker here yet -- that's formalized platform-wide in the
 * resilience phase (Phase 11). For now, any failure to reach product-service simply
 * fails the order-creation request with a 503 rather than silently proceeding with
 * unpriced or stale data.
 */
@Component
public class ProductServiceClient {

    private final RestClient restClient;

    public ProductServiceClient(RestClient.Builder restClientBuilder, ProductServiceProperties properties) {
        this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
    }

    public Optional<ProductSnapshot> getProduct(UUID productId) {
        try {
            ProductSnapshot snapshot = restClient.get()
                    .uri("/api/v1/products/{id}", productId)
                    .retrieve()
                    .body(ProductSnapshot.class);
            return Optional.ofNullable(snapshot);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw new ProductServiceUnavailableException(e);
        }
    }
}
