package com.smartdelivery.order.client;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * The saga's synchronous call into payment-service (docs/saga.md). Unlike
 * InventoryServiceClient, a decline is not an HTTP error here -- payment-service
 * always returns 201 with the outcome in the response body (see
 * payment-service's PaymentController), because "the charge was declined" is a
 * successfully processed payment attempt, not a broken API call. This client
 * reflects that: {@link #charge} returns a result the orchestrator branches on,
 * rather than throwing for the expected decline case -- there is no business
 * exception to exempt from the {@code payment-service} Resilience4j instance the way
 * InventoryServiceClient exempts {@code InsufficientStockException}. A genuine
 * connectivity/5xx failure still propagates unchanged, for the same Kafka-retry reason
 * documented on InventoryServiceClient (docs/resilience.md).
 */
@Component
public class PaymentServiceClient {

    private record PaymentApiResponse(UUID id, String status) {
    }

    private final RestClient restClient;
    private final ServiceTokenProvider tokenProvider;

    public PaymentServiceClient(
            RestClient.Builder restClientBuilder, PaymentServiceProperties properties, ServiceTokenProvider tokenProvider) {
        this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
        this.tokenProvider = tokenProvider;
    }

    @CircuitBreaker(name = "payment-service")
    @Retry(name = "payment-service")
    @Bulkhead(name = "payment-service")
    @RateLimiter(name = "payment-service")
    public PaymentChargeResult charge(UUID orderId, BigDecimal amount) {
        PaymentApiResponse response = restClient.post()
                .uri("/api/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .headers(this::propagateCorrelationId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("orderId", orderId, "amount", amount, "currency", "USD"))
                .retrieve()
                .body(PaymentApiResponse.class);

        return new PaymentChargeResult(response.id(), "SUCCESS".equals(response.status()));
    }

    /** Best-effort compensation call -- see OrderSagaOrchestrator. */
    @CircuitBreaker(name = "payment-service")
    @Retry(name = "payment-service")
    @Bulkhead(name = "payment-service")
    @RateLimiter(name = "payment-service")
    public void refund(UUID orderId) {
        restClient.post()
                .uri("/api/v1/payments/refund")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .headers(this::propagateCorrelationId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("orderId", orderId))
                .retrieve()
                .toBodilessEntity();
    }

    private String bearerToken() {
        return "Bearer " + tokenProvider.currentToken();
    }

    /**
     * Forwards this saga step's correlation id (originally set by CorrelationIdFilter
     * on the request that created the order, carried forward through Kafka by the saga
     * listeners' own MDC handling -- see docs/observability.md) to payment-service, so
     * its CorrelationIdFilter picks up the same id instead of minting an unrelated one.
     */
    private void propagateCorrelationId(HttpHeaders headers) {
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            headers.add("X-Correlation-Id", correlationId);
        }
    }
}
