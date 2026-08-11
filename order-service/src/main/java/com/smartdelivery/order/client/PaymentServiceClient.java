package com.smartdelivery.order.client;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import com.smartdelivery.order.security.InternalServiceTokenProvider;

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
 * rather than throwing for the expected decline case. A genuine connectivity/5xx
 * failure still propagates unchanged, for the same Kafka-retry reason documented on
 * InventoryServiceClient.
 */
@Component
public class PaymentServiceClient {

    private record PaymentApiResponse(UUID id, String status) {
    }

    private final RestClient restClient;
    private final InternalServiceTokenProvider tokenProvider;

    public PaymentServiceClient(
            RestClient.Builder restClientBuilder, PaymentServiceProperties properties, InternalServiceTokenProvider tokenProvider) {
        this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
        this.tokenProvider = tokenProvider;
    }

    public PaymentChargeResult charge(UUID orderId, BigDecimal amount) {
        PaymentApiResponse response = restClient.post()
                .uri("/api/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("orderId", orderId, "amount", amount, "currency", "USD"))
                .retrieve()
                .body(PaymentApiResponse.class);

        return new PaymentChargeResult(response.id(), "SUCCESS".equals(response.status()));
    }

    /** Best-effort compensation call -- see OrderSagaOrchestrator. */
    public void refund(UUID orderId) {
        restClient.post()
                .uri("/api/v1/payments/refund")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("orderId", orderId))
                .retrieve()
                .toBodilessEntity();
    }

    private String bearerToken() {
        return "Bearer " + tokenProvider.mintServiceToken();
    }
}
