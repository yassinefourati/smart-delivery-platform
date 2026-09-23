package com.smartdelivery.order.service;

import com.smartdelivery.order.client.InventoryServiceClient;
import com.smartdelivery.order.client.PaymentServiceClient;
import com.smartdelivery.order.client.ServiceTokenProvider;
import com.smartdelivery.order.client.UserServiceProperties;
import com.smartdelivery.order.domain.Order;
import com.smartdelivery.order.domain.OrderItem;
import com.smartdelivery.order.domain.OrderStatus;
import com.smartdelivery.order.repository.OrderRepository;
import com.smartdelivery.order.security.TestJwtIssuer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The reaper against a real database (ADR 008). Two of its three guarantees are
 * properties of the SQL rather than of the Java, and only show up here: that the claim is
 * exclusive between concurrently running instances, and that incrementing
 * {@code saga_attempts} takes out a lease by refreshing {@code updated_at}.
 *
 * Orders are inserted directly and backdated with JDBC, because "nothing has touched this
 * order for five minutes" is not a state any amount of exercising the real write path
 * would produce inside a test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@Import(StuckSagaReaperIntegrationTest.RestClientTestConfig.class)
class StuckSagaReaperIntegrationTest {

    private static final TestJwtIssuer JWT_ISSUER = new TestJwtIssuer();
    private static final Duration STUCK_THRESHOLD = Duration.ofMinutes(5);
    private static final int MAX_ATTEMPTS = 3;

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
        // Parks the application's own reaper for the whole run: the interval alone would
        // still let its first run fire at startup, racing the orders a test is inserting.
        registry.add("saga.reaper-interval-ms", () -> "3600000");
        registry.add("saga.reaper-initial-delay-ms", () -> "3600000");
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
    private OrderRepository orderRepository;

    @Autowired
    private OrderSagaOrchestrator orchestrator;

    @Autowired
    private InventoryServiceClient inventoryServiceClient;

    @Autowired
    private PaymentServiceClient paymentServiceClient;

    @Autowired
    private OrderSagaEventHandler eventHandler;

    @Autowired
    private MockRestServiceServer mockRestServiceServer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetMockServer() {
        mockRestServiceServer.reset();
    }

    private StuckSagaReaper reaper(OrderSagaOrchestrator withOrchestrator) {
        return new StuckSagaReaper(orderRepository, withOrchestrator,
                new SagaProperties(STUCK_THRESHOLD, MAX_ATTEMPTS, 50), new SimpleMeterRegistry(), transactionManager);
    }

    private Order persistStuckOrder(OrderStatus status, int sagaAttempts, UUID... productIds) {
        Order saved = new TransactionTemplate(transactionManager).execute(tx -> {
            Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), null, null);
            for (UUID productId : productIds) {
                order.addItem(new OrderItem(productId, "Widget", new BigDecimal("10.00"), 1));
            }
            ReflectionTestUtils.setField(order, "status", status);
            ReflectionTestUtils.setField(order, "sagaAttempts", sagaAttempts);
            return orderRepository.saveAndFlush(order);
        });
        backdate(saved.getId(), STUCK_THRESHOLD.plusMinutes(1));
        return saved;
    }

    /** @UpdateTimestamp would overwrite anything set through the entity, so go round it. */
    private void backdate(UUID orderId, Duration age) {
        jdbcTemplate.update("UPDATE orders SET updated_at = now() - CAST(? AS interval) WHERE id = ?",
                age.toSeconds() + " seconds", orderId);
    }

    private int sagaAttemptsOf(UUID orderId) {
        return jdbcTemplate.queryForObject("SELECT saga_attempts FROM orders WHERE id = ?", Integer.class, orderId);
    }

    private OrderStatus statusOf(UUID orderId) {
        return OrderStatus.valueOf(
                jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId));
    }

    @Test
    void restartsTheSagaOfAStuckOrderAndCarriesItToCompletion() {
        UUID productId = UUID.randomUUID();
        Order order = persistStuckOrder(OrderStatus.INVENTORY_RESERVATION_PENDING, 0, productId);
        mockRestServiceServer.expect(manyTimes(), requestTo("http://localhost:8083/api/v1/inventory/reserve"))
                .andRespond(withSuccess("{\"reservationId\":\"" + UUID.randomUUID() + "\",\"status\":\"RESERVED\"}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        mockRestServiceServer.expect(manyTimes(), requestTo("http://localhost:8085/api/v1/payments"))
                .andRespond(withSuccess("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"SUCCESS\"}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        mockRestServiceServer.expect(manyTimes(), requestTo("http://localhost:8083/api/v1/inventory/deduct"))
                .andRespond(withSuccess());

        reaper(orchestrator).reapStuckSagas();

        assertThat(statusOf(order.getId())).isEqualTo(OrderStatus.PAID);
        assertThat(sagaAttemptsOf(order.getId())).isEqualTo(1);
    }

    /**
     * An order that cannot be completed and is never released is worse than one honestly
     * marked FAILED -- so once the attempts run out, the reaper releases the stock, asks
     * for a refund, and ends the order.
     */
    @Test
    void compensatesAndFailsAnOrderThatHasExhaustedItsAttempts() {
        UUID productId = UUID.randomUUID();
        Order order = persistStuckOrder(OrderStatus.PAYMENT_PENDING, MAX_ATTEMPTS, productId);
        mockRestServiceServer.expect(manyTimes(), requestTo("http://localhost:8083/api/v1/inventory/release"))
                .andRespond(withSuccess());
        mockRestServiceServer.expect(manyTimes(), requestTo("http://localhost:8085/api/v1/payments/refund"))
                .andRespond(withSuccess());

        reaper(orchestrator).reapStuckSagas();

        assertThat(statusOf(order.getId())).isEqualTo(OrderStatus.FAILED);
        // Released before being failed, not instead of -- the stock is the whole point.
        mockRestServiceServer.verify();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'OrderFailed'",
                Integer.class, order.getId())).isEqualTo(1);
    }

    /** A healthy order that simply has not been touched recently must be left alone. */
    @Test
    void ignoresOrdersThatAreNotYetStuck() {
        Order order = persistStuckOrder(OrderStatus.INVENTORY_RESERVED, 0, UUID.randomUUID());
        backdate(order.getId(), Duration.ofMinutes(1));

        reaper(orchestrator).reapStuckSagas();

        assertThat(sagaAttemptsOf(order.getId())).isZero();
    }

    /**
     * The claim's whole job. Two instances polling the same table at the same moment must
     * not both take the same order: one is skipped by {@code SKIP LOCKED}, and the lease
     * the winner takes out (a refreshed {@code updated_at}) keeps it out of the loser's
     * next poll too.
     *
     * The orchestrator is stubbed here so "processed once" is directly countable rather
     * than inferred from downstream side effects on a mock server two threads are sharing.
     */
    @Test
    void twoReaperInstancesNeverProcessTheSameOrderTwice() throws Exception {
        List<UUID> stuck = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            stuck.add(persistStuckOrder(OrderStatus.INVENTORY_RESERVATION_PENDING, 0, UUID.randomUUID()).getId());
        }
        var startsPerOrder = new ConcurrentHashMap<UUID, AtomicInteger>();
        OrderSagaOrchestrator counting = new OrderSagaOrchestrator(
                orderRepository, inventoryServiceClient, paymentServiceClient, eventHandler) {
            @Override
            public void startSaga(UUID orderId) {
                startsPerOrder.computeIfAbsent(orderId, id -> new AtomicInteger()).incrementAndGet();
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> runs = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                StuckSagaReaper instance = reaper(counting);
                runs.add(executor.submit(() -> {
                    start.await();
                    instance.reapStuckSagas();
                    return null;
                }));
            }
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
            for (Future<?> run : runs) {
                run.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(startsPerOrder.keySet()).containsExactlyInAnyOrderElementsOf(stuck);
        assertThat(startsPerOrder.values()).allSatisfy(count -> assertThat(count.get()).isEqualTo(1));
        for (UUID orderId : stuck) {
            assertThat(sagaAttemptsOf(orderId)).isEqualTo(1);
        }
    }

    /**
     * The lease, stated directly: a claimed order leaves the eligible set immediately,
     * because the claim's own write refreshed {@code updated_at}. Without it a slow
     * compensation would be restarted by the very next poll.
     */
    @Test
    void aClaimedOrderIsNotClaimedAgainUntilItsLeaseExpires() {
        var never = new OrderSagaOrchestrator(
                orderRepository, inventoryServiceClient, paymentServiceClient, eventHandler) {
            @Override
            public void startSaga(UUID orderId) {
                // deliberately does nothing: the point is what the claim did, not the saga
            }
        };
        Order order = persistStuckOrder(OrderStatus.CREATED, 0, UUID.randomUUID());

        reaper(never).reapStuckSagas();
        reaper(never).reapStuckSagas();

        assertThat(sagaAttemptsOf(order.getId())).isEqualTo(1);
    }
}
