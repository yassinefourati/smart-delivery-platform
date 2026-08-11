package com.smartdelivery.order.client;

import com.smartdelivery.order.exception.InsufficientStockException;
import com.smartdelivery.order.security.InternalServiceTokenProvider;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * The saga's synchronous calls into inventory-service (docs/saga.md). Deliberately
 * distinguishes two failure shapes:
 *
 * <ul>
 *   <li>A clean 409 (insufficient stock) is a real business outcome -- translated to
 *       {@link InsufficientStockException}, which OrderSagaOrchestrator catches and
 *       compensates for. Configured (see application.yml's {@code ignore-exceptions})
 *       to count against neither the {@code inventory-service} circuit breaker nor its
 *       retry -- inventory-service isn't unhealthy just because a warehouse ran out of
 *       something, and retrying a definitive "no stock" answer would be wrong, not just
 *       wasteful.</li>
 *   <li>Everything else (connection refused, timeout, 5xx) is left to propagate
 *       unchanged, now through the {@code inventory-service} Resilience4j instance
 *       (circuit breaker/retry/bulkhead/rate limiter, Phase 11 -- see
 *       docs/resilience.md) before it does. Since these calls happen inside a
 *       {@code @KafkaListener} (OrderSagaStartListener), whatever still escapes --
 *       including Resilience4j's own {@code CallNotPermittedException} when the
 *       circuit is open -- is retried by KafkaConsumerConfig's DefaultErrorHandler (3
 *       attempts, then dead-letter), reusing Phase 6's infrastructure rather than
 *       hand-rolling retry logic here. No fallback method is configured for exactly
 *       this reason: a fallback that swallowed the failure would silently break the
 *       saga's resumability story (docs/saga.md#resumability).</li>
 * </ul>
 */
@Component
public class InventoryServiceClient {

    private final RestClient restClient;
    private final InternalServiceTokenProvider tokenProvider;

    public InventoryServiceClient(
            RestClient.Builder restClientBuilder, InventoryServiceProperties properties, InternalServiceTokenProvider tokenProvider) {
        this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
        this.tokenProvider = tokenProvider;
    }

    @CircuitBreaker(name = "inventory-service")
    @Retry(name = "inventory-service")
    @Bulkhead(name = "inventory-service")
    @RateLimiter(name = "inventory-service")
    public void reserve(UUID orderId, UUID productId, int quantity) {
        try {
            restClient.post()
                    .uri("/api/v1/inventory/reserve")
                    .header(HttpHeaders.AUTHORIZATION, bearerToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("orderId", orderId, "productId", productId, "quantity", quantity))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.Conflict e) {
            throw new InsufficientStockException(productId);
        }
    }

    /** Best-effort compensation call -- see OrderSagaOrchestrator.compensateReservations. */
    @CircuitBreaker(name = "inventory-service")
    @Retry(name = "inventory-service")
    @Bulkhead(name = "inventory-service")
    @RateLimiter(name = "inventory-service")
    public void release(UUID orderId, UUID productId) {
        restClient.post()
                .uri("/api/v1/inventory/release")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("orderId", orderId, "productId", productId))
                .retrieve()
                .toBodilessEntity();
    }

    @CircuitBreaker(name = "inventory-service")
    @Retry(name = "inventory-service")
    @Bulkhead(name = "inventory-service")
    @RateLimiter(name = "inventory-service")
    public void deduct(UUID orderId, UUID productId) {
        restClient.post()
                .uri("/api/v1/inventory/deduct")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("orderId", orderId, "productId", productId))
                .retrieve()
                .toBodilessEntity();
    }

    private String bearerToken() {
        return "Bearer " + tokenProvider.mintServiceToken();
    }
}
