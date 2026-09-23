package com.smartdelivery.order.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.order.client.ServiceTokenProvider;
import com.smartdelivery.order.client.UserServiceProperties;
import com.smartdelivery.order.domain.Order;
import com.smartdelivery.order.domain.OrderItem;
import com.smartdelivery.order.dto.CreateOrderRequest;
import com.smartdelivery.order.dto.OrderItemRequest;
import com.smartdelivery.order.repository.OrderRepository;
import com.smartdelivery.order.security.TestJwtIssuer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 23 (ADR 015): a slow product-service must not starve order-service of database
 * connections.
 *
 * Order creation prices every line with a synchronous call to product-service. Until
 * this phase that call ran inside the order's database transaction, so every in-flight
 * create held a pooled connection for as long as product-service took to answer --
 * with retries, up to about ten seconds. The production pool is a handful of
 * connections per pod, so a slow product-service pinned all of them, and everything
 * else in order-service that needs the database failed with a 503 after the pool's
 * connection timeout: status reads, the saga's Kafka listeners, the outbox. A load
 * test with 1.5s of injected product latency measured it: 12% of status reads failed
 * at exactly the pool timeout (docs/load-testing.md).
 *
 * The test makes the failure deterministic instead of statistical: ONE pooled
 * connection, a create parked inside a product lookup that does not answer, and a
 * status read -- which never calls product-service -- that must still succeed. With the
 * lookup inside the transaction, that read waits for the only connection, times out
 * after a second and returns 503.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@Import(OrderCreatePoolIsolationIntegrationTest.RestClientTestConfig.class)
class OrderCreatePoolIsolationIntegrationTest {

    private static final TestJwtIssuer JWT_ISSUER = new TestJwtIssuer();
    private static final String PRODUCT_URI = "http://localhost:8082/api/v1/products/";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JWT_ISSUER::jwkSetUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestJwtIssuer.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> TestJwtIssuer.AUDIENCE);
        // The whole point: one connection, and a short wait for it, so a held connection
        // shows up as a failed request rather than as a slow one.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "1000");
        // Nothing else may take the single connection while the test holds its breath:
        // the outbox poller, its cleanup and the saga reaper are parked (interval AND
        // initial delay, because a fixedDelay schedule's first run ignores the interval).
        registry.add("outbox.poll-interval-ms", () -> "3600000");
        registry.add("outbox.poll-initial-delay-ms", () -> "3600000");
        registry.add("outbox.cleanup-interval-ms", () -> "3600000");
        registry.add("outbox.cleanup-initial-delay-ms", () -> "3600000");
        registry.add("saga.reaper-interval-ms", () -> "3600000");
        registry.add("saga.reaper-initial-delay-ms", () -> "3600000");
    }

    /** Same binding as OrderApiIntegrationTest's; see the reasoning there. */
    @TestConfiguration
    static class RestClientTestConfig {
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
        @Primary
        ServiceTokenProvider testServiceTokenProvider() {
            return new ServiceTokenProvider(RestClient.builder(),
                    new UserServiceProperties("http://user-service.invalid", "order-service", "secret")) {
                @Override
                public String currentToken() {
                    return "stub-service-token";
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockRestServiceServer mockRestServiceServer;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void aSlowProductLookupDoesNotHoldADatabaseConnection() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = "Bearer " + JWT_ISSUER.token(userId, "CUSTOMER");
        Order existing = persistOrder(userId);

        UUID productId = UUID.randomUUID();
        String product = objectMapper.writeValueAsString(Map.of(
                "id", productId.toString(), "name", "Widget", "price", "9.99", "active", true));
        CountDownLatch lookupStarted = new CountDownLatch(1);
        CountDownLatch productServiceAnswers = new CountDownLatch(1);
        mockRestServiceServer.reset();
        mockRestServiceServer.expect(requestTo(PRODUCT_URI + productId)).andRespond(request -> {
            // A product-service that has stopped answering, until the test says otherwise.
            lookupStarted.countDown();
            try {
                productServiceAnswers.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return withSuccess(product, MediaType.APPLICATION_JSON).createResponse(request);
        });

        String body = objectMapper.writeValueAsString(new CreateOrderRequest(UUID.randomUUID(),
                List.of(new OrderItemRequest(productId, 1))));
        ExecutorService client = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> create = client.submit(() -> mockMvc.perform(post("/api/v1/orders")
                    .header("Authorization", token)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andReturn());
            assertThat(lookupStarted.await(10, TimeUnit.SECONDS))
                    .as("the create request reached product-service").isTrue();

            // The create is now parked inside the product lookup. The one pooled connection
            // must be free: a read that never calls product-service has to succeed.
            int statusRead;
            try {
                statusRead = mockMvc.perform(get("/api/v1/orders/{id}/status", existing.getId())
                        .header("Authorization", token)).andReturn().getResponse().getStatus();
            } finally {
                productServiceAnswers.countDown();
            }
            assertThat(statusRead)
                    .as("a status read while a product lookup is in flight (503 = the lookup held the only connection)")
                    .isEqualTo(200);

            // And the create itself still completes normally once product-service answers.
            assertThat(create.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
        } finally {
            productServiceAnswers.countDown();
            client.shutdownNow();
        }
        mockRestServiceServer.verify();
    }

    private Order persistOrder(UUID userId) {
        return new TransactionTemplate(transactionManager).execute(tx -> {
            Order order = new Order(userId, UUID.randomUUID(), null, null);
            order.addItem(new OrderItem(UUID.randomUUID(), "Widget", new BigDecimal("10.00"), 1));
            return orderRepository.saveAndFlush(order);
        });
    }
}
