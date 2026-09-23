package com.smartdelivery.order.service;

import com.smartdelivery.order.client.ProductServiceClient;
import com.smartdelivery.order.client.ProductServiceProperties;
import com.smartdelivery.order.client.ProductSnapshot;
import com.smartdelivery.order.domain.Order;
import com.smartdelivery.order.dto.CreateOrderRequest;
import com.smartdelivery.order.dto.OrderItemRequest;
import com.smartdelivery.order.security.TestJwtIssuer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two requests with the same Idempotency-Key, genuinely concurrent, against real PostgreSQL.
 *
 * This is the double-click the key exists to absorb, and until this test it returned a 500:
 * both requests passed the "already exists?" check, both inserted, the unique index refused
 * the second, and the code that was meant to recover re-read the winner INSIDE the transaction
 * PostgreSQL had just aborted. A mocked repository cannot show that -- the abort is the
 * database's behaviour -- so this runs the real service against a real database.
 *
 * The race is forced, not hoped for: product lookup happens after the existence check and
 * before the insert, so a barrier there makes both threads pass the check before either
 * inserts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@Import(ConcurrentIdempotentCreateIntegrationTest.BarrierProductClientConfig.class)
class ConcurrentIdempotentCreateIntegrationTest {

    private static final TestJwtIssuer JWT_ISSUER = new TestJwtIssuer();
    private static final UUID PRODUCT_ID = UUID.randomUUID();

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
    }

    /** Both requests must reach the insert together; a two-party barrier in the product lookup does it. */
    static final CyclicBarrier BOTH_PAST_THE_EXISTENCE_CHECK = new CyclicBarrier(2);

    @TestConfiguration
    static class BarrierProductClientConfig {
        @Bean
        @Primary
        ProductServiceClient barrierProductServiceClient() {
            return new ProductServiceClient(RestClient.builder(), new ProductServiceProperties("http://product-service.invalid")) {
                @Override
                public Optional<ProductSnapshot> getProduct(UUID productId) {
                    try {
                        BOTH_PAST_THE_EXISTENCE_CHECK.await(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException("the second request never arrived", e);
                    }
                    return Optional.of(new ProductSnapshot(productId, "Widget", new BigDecimal("9.99"), true));
                }
            };
        }
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void twoConcurrentRequestsWithTheSameKeyCreateOneOrderAndBothGetIt() throws Exception {
        UUID userId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        var request = new CreateOrderRequest(UUID.randomUUID(), List.of(new OrderItemRequest(PRODUCT_ID, 2)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Order> first = pool.submit(() -> orderService.create(userId, key, request));
            Future<Order> second = pool.submit(() -> orderService.create(userId, key, request));

            UUID a = first.get(30, TimeUnit.SECONDS).getId();
            UUID b = second.get(30, TimeUnit.SECONDS).getId();

            assertThat(a).isEqualTo(b);
        } finally {
            pool.shutdownNow();
        }

        Integer orders = jdbcTemplate.queryForObject(
                "select count(*) from orders where user_id = ? and idempotency_key = ?", Integer.class, userId, key);
        assertThat(orders).isEqualTo(1);
        // One order, announced once: the losing request must not write a second OrderCreated.
        Integer created = jdbcTemplate.queryForObject(
                "select count(*) from outbox_events where aggregate_id = (select id from orders where user_id = ? and idempotency_key = ?) and event_type = 'OrderCreated'",
                Integer.class, userId, key);
        assertThat(created).isEqualTo(1);
    }
}
