package com.smartdelivery.order.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.order.domain.Order;
import com.smartdelivery.order.domain.OrderStatus;
import com.smartdelivery.order.repository.OrderRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static io.jsonwebtoken.security.Keys.hmacShaKeyFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the Kafka producer/consumer/retry/DLT plumbing actually works end to end
 * against real brokers (Testcontainers), not just that the Spring wiring compiles:
 * order creation lands a real message on order.created, a raw InventoryReserved
 * message advances a pre-conditioned order's status, and a message that can never be
 * parsed ends up on the dead-letter topic after retries are exhausted rather than
 * wedging the consumer forever.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@Import(OrderKafkaIntegrationTest.RestClientTestConfig.class)
class OrderKafkaIntegrationTest {

    private static final String JWT_SECRET = "integration-test-secret-key-must-be-at-least-32-bytes";
    private static final SecretKey SIGNING_KEY = hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));

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
        registry.add("jwt.secret", () -> JWT_SECRET);
    }

    @TestConfiguration
    static class RestClientTestConfig {
        @Bean
        RestClient.Builder testRestClientBuilder() {
            return RestClient.builder();
        }

        @Bean
        MockRestServiceServer mockRestServiceServer(RestClient.Builder builder) {
            return MockRestServiceServer.bindTo(builder).build();
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

    private KafkaConsumer<String, String> testConsumer;

    @BeforeEach
    void setUp() {
        mockRestServiceServer.reset();
        testConsumer = newRawConsumer();
    }

    @AfterEach
    void tearDown() {
        testConsumer.close();
    }

    private KafkaConsumer<String, String> newRawConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-probe-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }

    private ConsumerRecord<String, String> consumeOne(String topic, Duration timeout) {
        testConsumer.subscribe(List.of(topic));
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            var records = testConsumer.poll(Duration.ofMillis(500));
            var iterator = records.iterator();
            if (iterator.hasNext()) {
                return iterator.next();
            }
        }
        throw new AssertionError("No message consumed from topic '%s' within %s".formatted(topic, timeout));
    }

    private String tokenFor(UUID userId, String role) {
        Instant now = Instant.now();
        return io.jsonwebtoken.Jwts.builder()
                .subject(userId.toString())
                .claim("roles", List.of(role))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(SIGNING_KEY)
                .compact();
    }

    private void publishRawEvent(String topic, String eventType, Object payload) throws Exception {
        var envelope = new EventEnvelope(UUID.randomUUID(), eventType, 1, Instant.now(), UUID.randomUUID(), "test", objectMapper.valueToTree(payload));
        kafkaTemplate.send(topic, objectMapper.writeValueAsString(envelope)).get();
    }

    @Test
    void creatingAnOrderPublishesOrderCreatedToKafka() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String body = objectMapper.writeValueAsString(Map.of(
                "id", productId.toString(), "name", "Widget", "price", "9.99", "active", true));
        mockRestServiceServer.expect(requestTo("http://localhost:8082/api/v1/products/" + productId))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        var orderRequest = Map.of("shippingAddressId", UUID.randomUUID().toString(),
                "items", List.of(Map.of("productId", productId.toString(), "quantity", 2)));

        String response = mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + tokenFor(userId, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = objectMapper.readTree(response).get("id").asText();

        var record = consumeOne(KafkaTopics.ORDER_CREATED, Duration.ofSeconds(15));
        var envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        var payload = objectMapper.treeToValue(envelope.payload(), OrderCreatedPayload.class);

        assertThat(envelope.eventType()).isEqualTo("OrderCreated");
        assertThat(payload.orderId()).isEqualTo(UUID.fromString(orderId));
        assertThat(payload.userId()).isEqualTo(userId);
    }

    @Test
    void consumingInventoryReservedAdvancesAPreconditionedOrder() throws Exception {
        Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), null, null);
        order = orderRepository.save(order);
        order.transitionTo(OrderStatus.INVENTORY_RESERVATION_PENDING);
        orderRepository.saveAndFlush(order);
        UUID orderId = order.getId();

        publishRawEvent(KafkaTopics.INVENTORY_RESERVED, "InventoryReserved",
                new InventoryReservedPayload(UUID.randomUUID(), orderId, UUID.randomUUID(), 1));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var updated = orderRepository.findById(orderId).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
        });
    }

    @Test
    void aMessageThatCanNeverBeParsedEndsUpOnTheDeadLetterTopic() throws Exception {
        kafkaTemplate.send(KafkaTopics.PAYMENT_COMPLETED, "this is not valid json").get();

        var dltRecord = consumeOne(KafkaTopics.PAYMENT_COMPLETED + ".DLT", Duration.ofSeconds(20));

        assertThat(dltRecord.value()).isEqualTo("this is not valid json");
    }
}
