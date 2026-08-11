package com.smartdelivery.order.client;

import com.smartdelivery.order.exception.InsufficientStockException;
import com.smartdelivery.order.security.InternalServiceTokenProvider;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.springboot3.bulkhead.autoconfigure.BulkheadAutoConfiguration;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.ratelimiter.autoconfigure.RateLimiterAutoConfiguration;
import io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Verifies the one Resilience4j behavior this platform actually depends on being
 * correct (docs/resilience.md): the {@code inventory-service} circuit breaker/retry
 * must not react to {@link InsufficientStockException} (a real business outcome) the
 * same way it reacts to a genuine infrastructure failure. Gets this wrong in either
 * direction and either a routine "no stock" answer starts tripping the circuit for
 * every other in-flight order, or a real outage never gets detected.
 *
 * Deliberately not a full {@code @SpringBootTest} of the whole application (which
 * would need Postgres and Kafka, like every other order-service integration test) --
 * this is a narrow slice built from exactly the beans and autoconfiguration this
 * behavior depends on (the client, its Resilience4j annotations, and Spring's AOP
 * proxying), so it can actually run without Testcontainers.
 */
@SpringBootTest(classes = ResilienceIntegrationTest.TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ImportAutoConfiguration({
        AopAutoConfiguration.class,
        CircuitBreakerAutoConfiguration.class,
        RetryAutoConfiguration.class,
        BulkheadAutoConfiguration.class,
        RateLimiterAutoConfiguration.class
})
@TestPropertySource(properties = {
        "jwt.secret=integration-test-secret-key-must-be-at-least-32-bytes",
        // Small, fast thresholds so this test needs only a handful of calls, not the
        // application.yml defaults (sliding-window-size: 10) sized for production noise.
        "resilience4j.circuitbreaker.instances.inventory-service.sliding-window-size=4",
        "resilience4j.circuitbreaker.instances.inventory-service.minimum-number-of-calls=4",
        "resilience4j.circuitbreaker.instances.inventory-service.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.inventory-service.wait-duration-in-open-state=1m",
        "resilience4j.circuitbreaker.instances.inventory-service.ignore-exceptions[0]=com.smartdelivery.order.exception.InsufficientStockException",
        // Isolates circuit-breaker behavior: one invocation, one underlying HTTP call,
        // so the test's call-count arithmetic against MockRestServiceServer stays exact.
        "resilience4j.retry.instances.inventory-service.max-attempts=1",
        "resilience4j.retry.instances.inventory-service.ignore-exceptions[0]=com.smartdelivery.order.exception.InsufficientStockException",
        "resilience4j.bulkhead.instances.inventory-service.max-concurrent-calls=20",
        "resilience4j.ratelimiter.instances.inventory-service.limit-for-period=1000",
        "resilience4j.ratelimiter.instances.inventory-service.limit-refresh-period=1s",
        "resilience4j.ratelimiter.instances.inventory-service.timeout-duration=1s"
})
class ResilienceIntegrationTest {

    /** See OrderApiIntegrationTest.RestClientTestConfig's Javadoc for why binding is atomic with creation. */
    @Configuration
    static class TestConfig {
        static final AtomicReference<MockRestServiceServer> SERVER_HOLDER = new AtomicReference<>();

        @Bean
        RestClient.Builder testRestClientBuilder() {
            RestClient.Builder builder = RestClient.builder();
            SERVER_HOLDER.set(MockRestServiceServer.bindTo(builder).build());
            return builder;
        }

        @Bean
        MockRestServiceServer mockRestServiceServer(RestClient.Builder builder) {
            return SERVER_HOLDER.get();
        }

        @Bean
        InventoryServiceProperties inventoryServiceProperties() {
            return new InventoryServiceProperties("http://localhost:8083");
        }

        @Bean
        InternalServiceTokenProvider internalServiceTokenProvider(
                org.springframework.core.env.Environment env) {
            return new InternalServiceTokenProvider(env.getRequiredProperty("jwt.secret"));
        }

        @Bean
        InventoryServiceClient inventoryServiceClient(
                RestClient.Builder builder, InventoryServiceProperties properties, InternalServiceTokenProvider tokenProvider) {
            return new InventoryServiceClient(builder, properties, tokenProvider);
        }
    }

    @Autowired
    private InventoryServiceClient inventoryServiceClient;

    @Autowired
    private MockRestServiceServer mockRestServiceServer;

    @BeforeEach
    void resetMockServer() {
        mockRestServiceServer.reset();
    }

    @Test
    void repeatedInsufficientStockDoesNotOpenTheCircuitBreaker() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        for (int i = 0; i < 6; i++) {
            mockRestServiceServer.expect(requestTo("http://localhost:8083/api/v1/inventory/reserve"))
                    .andRespond(withStatus(HttpStatus.CONFLICT));
        }

        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> inventoryServiceClient.reserve(orderId, productId, 1))
                    .isInstanceOf(InsufficientStockException.class);
        }

        // Every one of the 6 calls actually reached the mock server -- if the circuit
        // had (incorrectly) opened partway through, a later call would have thrown
        // CallNotPermittedException instead of InsufficientStockException, and this
        // verify() would fail because not every expected request was made.
        mockRestServiceServer.verify();
    }

    @Test
    void repeatedInfrastructureFailuresOpenTheCircuitBreakerAndTheNextCallFailsFast() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            mockRestServiceServer.expect(requestTo("http://localhost:8083/api/v1/inventory/release"))
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        }

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> inventoryServiceClient.release(orderId, productId))
                    .isNotInstanceOf(CallNotPermittedException.class);
        }

        // The circuit is now OPEN (4/4 failures >= the 50% threshold over a
        // minimum-number-of-calls: 4 window). A 5th call must fail immediately with
        // CallNotPermittedException, not reach the network -- MockRestServiceServer has
        // no expectation left to satisfy it, so it would throw its own "no further
        // requests expected" AssertionError if the circuit hadn't actually opened.
        assertThatThrownBy(() -> inventoryServiceClient.release(orderId, productId))
                .isInstanceOf(CallNotPermittedException.class);

        mockRestServiceServer.verify();
    }
}
