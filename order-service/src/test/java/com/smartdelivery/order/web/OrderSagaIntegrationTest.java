package com.smartdelivery.order.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.order.domain.OrderStatus;
import com.smartdelivery.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import com.smartdelivery.order.security.TestJwtIssuer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import com.smartdelivery.order.client.UserServiceProperties;
import com.smartdelivery.order.client.ServiceTokenProvider;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the whole Order -&gt; Inventory -&gt; Payment saga (docs/saga.md) end to end:
 * an order created over the real HTTP API is picked up by OrderSagaStartListener
 * (consuming order-service's own order.created event over a real Kafka broker),
 * driven by OrderSagaOrchestrator through real REST calls -- to
 * inventory-service and payment-service, both intercepted with
 * {@link MockRestServiceServer} rather than stood up for real, keeping order-service
 * independently testable (docs/service-boundaries.md) -- and lands the order in its
 * correct final state, polled for since the saga runs asynchronously off the Kafka
 * consumer thread.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@Import(OrderSagaIntegrationTest.RestClientTestConfig.class)
class OrderSagaIntegrationTest {

    /**
     * Stands in for user-service: a real JWKS endpoint over HTTP, so these tests
     * exercise the same key-fetch-and-select path production does (ADR 007).
     */
    private static final TestJwtIssuer JWT_ISSUER = new TestJwtIssuer();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JWT_ISSUER::jwkSetUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestJwtIssuer.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> TestJwtIssuer.AUDIENCE);
    }

    /** See OrderApiIntegrationTest.RestClientTestConfig's Javadoc for why binding is atomic with creation. */
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

        /**
         * The real provider would fetch a SERVICE token through this same bound builder,
         * turning an unrelated call into an unexpected-request failure in every test
         * here. What that fetch actually does, and what happens without it, is covered by
         * JwtResourceServerIntegrationTest and user-service's own tests (ADR 007).
         */
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

    @BeforeEach
    void resetMockServer() {
        mockRestServiceServer.reset();
    }

    private String tokenFor(UUID userId) {
        return JWT_ISSUER.token(userId, "CUSTOMER");
    }

    private void stubProduct(UUID productId) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "id", productId.toString(), "name", "Widget", "price", "25.00", "active", true));
        mockRestServiceServer.expect(requestTo("http://localhost:8082/api/v1/products/" + productId))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void stubReserveSucceeds() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "reservationId", UUID.randomUUID().toString(), "status", "RESERVED"));
        mockRestServiceServer.expect(requestTo("http://localhost:8083/api/v1/inventory/reserve"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void stubReserveFailsWithInsufficientStock() {
        mockRestServiceServer.expect(requestTo("http://localhost:8083/api/v1/inventory/reserve"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CONFLICT));
    }

    private void stubReleaseSucceeds() {
        mockRestServiceServer.expect(requestTo("http://localhost:8083/api/v1/inventory/release"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.OK));
    }

    private void stubDeductSucceeds() {
        mockRestServiceServer.expect(requestTo("http://localhost:8083/api/v1/inventory/deduct"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.OK));
    }

    private void stubChargeSucceeds() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("id", UUID.randomUUID().toString(), "status", "SUCCESS"));
        mockRestServiceServer.expect(requestTo("http://localhost:8085/api/v1/payments"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void stubChargeDeclines() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("id", UUID.randomUUID().toString(), "status", "FAILED"));
        mockRestServiceServer.expect(requestTo("http://localhost:8085/api/v1/payments"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private UUID createOrder(UUID productId, UUID userId) throws Exception {
        var request = Map.of("shippingAddressId", UUID.randomUUID().toString(),
                "items", List.of(Map.of("productId", productId.toString(), "quantity", 1)));
        String response = mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + tokenFor(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    @Test
    void happyPathSagaReservesChargesAndMarksTheOrderPaid() throws Exception {
        UUID productId = UUID.randomUUID();
        stubProduct(productId);
        stubReserveSucceeds();
        stubChargeSucceeds();
        stubDeductSucceeds();

        UUID orderId = createOrder(productId, UUID.randomUUID());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            var order = orderRepository.findById(orderId).orElseThrow();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        });

        mockRestServiceServer.verify();
    }

    @Test
    void insufficientStockDrivesTheOrderToFailed() throws Exception {
        UUID productId = UUID.randomUUID();
        stubProduct(productId);
        stubReserveFailsWithInsufficientStock();

        UUID orderId = createOrder(productId, UUID.randomUUID());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            var order = orderRepository.findById(orderId).orElseThrow();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        });
    }

    @Test
    void aDeclinedPaymentReleasesTheReservationAndCancelsTheOrder() throws Exception {
        UUID productId = UUID.randomUUID();
        stubProduct(productId);
        stubReserveSucceeds();
        stubChargeDeclines();
        stubReleaseSucceeds();

        UUID orderId = createOrder(productId, UUID.randomUUID());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            var order = orderRepository.findById(orderId).orElseThrow();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        });

        // Confirms the release call was actually made, not just that the status matches
        // by coincidence.
        mockRestServiceServer.verify();
    }
}
