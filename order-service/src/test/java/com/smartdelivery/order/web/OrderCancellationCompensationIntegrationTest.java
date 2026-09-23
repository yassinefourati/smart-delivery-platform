package com.smartdelivery.order.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.order.client.ServiceTokenProvider;
import com.smartdelivery.order.client.UserServiceProperties;
import com.smartdelivery.order.domain.Order;
import com.smartdelivery.order.domain.OrderItem;
import com.smartdelivery.order.domain.OrderStatus;
import com.smartdelivery.order.event.KafkaTopics;
import com.smartdelivery.order.repository.OrderRepository;
import com.smartdelivery.order.security.TestJwtIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The gap Phase 17 closed (ADR 008): cancellation compensation used to run inline in the
 * cancel request, after {@code OrderService.cancel} had already committed. A failed refund,
 * or a pod dying in that window, left the order CANCELLED with its stock still held and
 * its payment still taken, and nothing anywhere would ever try again.
 *
 * These tests drive the real path end to end over a real broker: cancel over HTTP, an
 * outbox row written in the cancelling transaction, {@code OutboxPublisher} sending it,
 * and {@code OrderCancellationListener} compensating off the event -- with Spring Kafka's
 * retry doing the part the old code had no mechanism for.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@Import(OrderCancellationCompensationIntegrationTest.RestClientTestConfig.class)
class OrderCancellationCompensationIntegrationTest {

    private static final TestJwtIssuer JWT_ISSUER = new TestJwtIssuer();
    private static final String REFUND_URI = "http://localhost:8085/api/v1/payments/refund";
    private static final String RELEASE_URI = "http://localhost:8083/api/v1/inventory/release";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JWT_ISSUER::jwkSetUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestJwtIssuer.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> TestJwtIssuer.AUDIENCE);
        // One HTTP call per logical attempt, so the request counts below say something
        // about Spring Kafka's retry -- which is what this test is about -- rather than
        // about Resilience4j's, which has its own test (ResilienceIntegrationTest).
        registry.add("resilience4j.retry.instances.payment-service.max-attempts", () -> "1");
        registry.add("resilience4j.retry.instances.inventory-service.max-attempts", () -> "1");
        // The reaper would otherwise notice these deliberately-stalled orders.
        registry.add("saga.reaper-interval-ms", () -> "3600000");
    }

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

        /** See OrderApiIntegrationTest's copy: the real provider would fetch through this same bound builder. */
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
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetMockServer() {
        mockRestServiceServer.reset();
    }

    private Order persistOrder(OrderStatus status, UUID... productIds) {
        return new TransactionTemplate(transactionManager).execute(tx -> {
            Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), null, null);
            for (UUID productId : productIds) {
                order.addItem(new OrderItem(productId, "Widget", new BigDecimal("10.00"), 1));
            }
            ReflectionTestUtils.setField(order, "status", status);
            return orderRepository.saveAndFlush(order);
        });
    }

    private void cancel(Order order) throws Exception {
        mockMvc.perform(post("/api/v1/orders/{id}/cancel", order.getId())
                        .header("Authorization", "Bearer " + JWT_ISSUER.token(order.getUserId(), "CUSTOMER")))
                .andExpect(status().isOk());
    }

    /**
     * The headline scenario. The cancel request succeeds and returns the cancelled order
     * -- unchanged behavior -- and the refund that fails the first time is retried off
     * the event until it lands. Before Phase 17 the single failed call was logged and the
     * money was simply never returned.
     */
    @Test
    void aRefundThatFailsOnceIsRetriedOffTheEventUntilItSucceeds() throws Exception {
        Order order = persistOrder(OrderStatus.PAID, UUID.randomUUID());
        mockRestServiceServer.expect(once(), requestTo(REFUND_URI))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        mockRestServiceServer.expect(once(), requestTo(REFUND_URI)).andRespond(withSuccess());

        cancel(order);

        // verify() passes only once both expectations have been met: one failure, then
        // one success, and no third call.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> mockRestServiceServer.verify());
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    /** A cancellation before payment releases every line rather than refunding anything. */
    @Test
    void cancellingBeforePaymentReleasesEveryReservedLine() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Order order = persistOrder(OrderStatus.INVENTORY_RESERVED, first, second);
        mockRestServiceServer.expect(once(), requestTo(RELEASE_URI)).andRespond(withSuccess());
        mockRestServiceServer.expect(once(), requestTo(RELEASE_URI)).andRespond(withSuccess());

        cancel(order);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> mockRestServiceServer.verify());
    }

    /**
     * Kafka is at-least-once, so the same {@code order.cancelled} can arrive twice. What
     * order-service guarantees is that a redelivery produces the *same* compensation
     * calls, driven entirely by the event's {@code previousStatus}, rather than a
     * different or escalating one -- and that the order itself is untouched by the second
     * pass.
     *
     * That those repeated calls have no repeated effect is the downstream's guarantee, and
     * is tested where it lives: {@code InventoryApiIntegrationTest} (releasing an
     * already-released reservation is a no-op) and {@code PaymentServiceTest}
     * (refunding an already-refunded payment is an idempotent no-op).
     */
    @Test
    void aRedeliveredCancellationCompensatesIdenticallyAndLeavesTheOrderAlone() throws Exception {
        Order order = persistOrder(OrderStatus.PAID, UUID.randomUUID());
        mockRestServiceServer.expect(once(), requestTo(REFUND_URI)).andRespond(withSuccess());
        mockRestServiceServer.expect(once(), requestTo(REFUND_URI)).andRespond(withSuccess());

        cancel(order);
        kafkaTemplate.send(KafkaTopics.ORDER_CANCELLED, order.getId().toString(),
                cancellationEvent(order, OrderStatus.PAID));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> mockRestServiceServer.verify());
        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    private String cancellationEvent(Order order, OrderStatus previousStatus) throws Exception {
        var payload = objectMapper.createObjectNode()
                .put("orderId", order.getId().toString())
                .put("userId", order.getUserId().toString())
                .put("reason", "Cancelled by customer")
                .put("previousStatus", previousStatus.name());
        var envelope = objectMapper.createObjectNode()
                .put("eventId", UUID.randomUUID().toString())
                .put("eventType", "OrderCancelled")
                .put("eventVersion", 1)
                .put("timestamp", java.time.Instant.now().toString())
                .put("correlationId", UUID.randomUUID().toString())
                .put("source", "order-service");
        envelope.set("payload", payload);
        return objectMapper.writeValueAsString(envelope);
    }
}
