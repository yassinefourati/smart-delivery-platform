package com.smartdelivery.payment.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.payment.event.EventEnvelope;
import com.smartdelivery.payment.event.KafkaTopics;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end verification through the real HTTP + Spring Security filter chain
 * against a real Postgres and Kafka (Testcontainers).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class PaymentApiIntegrationTest {

    private static final String JWT_SECRET = "integration-test-secret-key-must-be-at-least-32-bytes";
    private static final SecretKey SIGNING_KEY = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));

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
        registry.add("payment.decline-threshold", () -> "10000.00");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private KafkaConsumer<String, String> testConsumer;

    @BeforeEach
    void setUp() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-probe-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        testConsumer = new KafkaConsumer<>(props);
    }

    @AfterEach
    void tearDown() {
        testConsumer.close();
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

    private String tokenWithRole(String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("roles", List.of(role))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(SIGNING_KEY)
                .compact();
    }

    @Test
    void chargingBelowTheThresholdSucceedsAndPublishesPaymentCompleted() throws Exception {
        UUID orderId = UUID.randomUUID();
        var request = Map.of("orderId", orderId.toString(), "amount", "99.99", "currency", "USD");

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + tokenWithRole("SERVICE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        var record = consumeOne(KafkaTopics.PAYMENT_COMPLETED, Duration.ofSeconds(15));
        var envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        assertThat(envelope.eventType()).isEqualTo("PaymentCompleted");
        assertThat(envelope.payload().get("orderId").asText()).isEqualTo(orderId.toString());
    }

    @Test
    void chargingAtOrAboveTheThresholdIsDeclinedAndPublishesPaymentFailed() throws Exception {
        UUID orderId = UUID.randomUUID();
        var request = Map.of("orderId", orderId.toString(), "amount", "10000.00", "currency", "USD");

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + tokenWithRole("SERVICE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"));

        var record = consumeOne(KafkaTopics.PAYMENT_FAILED, Duration.ofSeconds(15));
        var envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        assertThat(envelope.eventType()).isEqualTo("PaymentFailed");
    }

    @Test
    void repeatedChargeForTheSameOrderReturnsTheOriginalPaymentWithoutDoubleCharging() throws Exception {
        UUID orderId = UUID.randomUUID();
        var request = Map.of("orderId", orderId.toString(), "amount", "20.00", "currency", "USD");
        String token = tokenWithRole("SERVICE");

        String first = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String firstId = objectMapper.readTree(first).get("id").asText();
        String secondId = objectMapper.readTree(second).get("id").asText();
        assertThat(secondId).isEqualTo(firstId);
    }

    @Test
    void chargingWithoutAdminOrServiceRoleReturns403() throws Exception {
        var request = Map.of("orderId", UUID.randomUUID().toString(), "amount", "20.00", "currency", "USD");

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + tokenWithRole("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void refundingASuccessfulPaymentMarksItRefunded() throws Exception {
        UUID orderId = UUID.randomUUID();
        var chargeRequest = Map.of("orderId", orderId.toString(), "amount", "30.00", "currency", "USD");
        String token = tokenWithRole("SERVICE");

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(chargeRequest)))
                .andExpect(status().isCreated());

        var refundRequest = Map.of("orderId", orderId.toString());
        mockMvc.perform(post("/api/v1/payments/refund")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refundRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    @Test
    void gettingAPaymentByIdReturnsItsCurrentState() throws Exception {
        UUID orderId = UUID.randomUUID();
        var request = Map.of("orderId", orderId.toString(), "amount", "15.00", "currency", "USD");
        String token = tokenWithRole("SERVICE");

        String createResponse = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String paymentId = objectMapper.readTree(createResponse).get("id").asText();

        mockMvc.perform(get("/api/v1/payments/{id}", paymentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()));
    }
}
