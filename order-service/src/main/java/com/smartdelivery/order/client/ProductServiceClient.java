package com.smartdelivery.order.client;

import com.smartdelivery.order.exception.ProductServiceUnavailableException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
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
 * A 404 (no such product) is a normal outcome -- {@link Optional#empty()}, not a
 * failure -- so it's resolved before any exception exists for Resilience4j to see; only
 * a real connectivity/5xx failure ({@link ProductServiceUnavailableException}) counts
 * against the {@code product-service} circuit breaker/retry (docs/resilience.md). This
 * call is on the synchronous order-creation request path (unlike the saga's calls,
 * which run inside a `@KafkaListener` and can rely on Kafka's own retry), so an open
 * circuit here surfaces to the client as a `503` (see GlobalExceptionHandler) rather
 * than a silently-hanging request.
 */
@Component
public class ProductServiceClient {

    private final RestClient restClient;

    public ProductServiceClient(RestClient.Builder restClientBuilder, ProductServiceProperties properties) {
        this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
    }

    @CircuitBreaker(name = "product-service")
    @Retry(name = "product-service")
    @Bulkhead(name = "product-service")
    @RateLimiter(name = "product-service")
    public Optional<ProductSnapshot> getProduct(UUID productId) {
        try {
            ProductSnapshot snapshot = restClient.get()
                    .uri("/api/v1/products/{id}", productId)
                    .headers(this::propagateCorrelationId)
                    .retrieve()
                    .body(ProductSnapshot.class);
            return Optional.ofNullable(snapshot);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw new ProductServiceUnavailableException(e);
        }
    }

    /**
     * Forwards this request's correlation id (set by CorrelationIdFilter, see
     * docs/observability.md) to product-service, so its own CorrelationIdFilter picks
     * up the same id instead of minting an unrelated one -- one id for the whole
     * request across every service it touches.
     */
    private void propagateCorrelationId(HttpHeaders headers) {
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            headers.add("X-Correlation-Id", correlationId);
        }
    }
}
