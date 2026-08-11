package com.smartdelivery.order.client;

import com.smartdelivery.order.exception.InsufficientStockException;
import com.smartdelivery.order.security.InternalServiceTokenProvider;
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
 *       compensates for.</li>
 *   <li>Everything else (connection refused, timeout, 5xx) is left to propagate
 *       unchanged. Since these calls happen inside a {@code @KafkaListener}
 *       (OrderSagaStartListener), an uncaught exception there is retried by
 *       KafkaConsumerConfig's DefaultErrorHandler (3 attempts, then dead-letter) --
 *       reusing Phase 6's infrastructure instead of hand-rolling retry logic here.
 *       Full REST-call resilience (circuit breaker, bounded timeout) is Phase 11's
 *       job.</li>
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
    public void release(UUID orderId, UUID productId) {
        restClient.post()
                .uri("/api/v1/inventory/release")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("orderId", orderId, "productId", productId))
                .retrieve()
                .toBodilessEntity();
    }

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
