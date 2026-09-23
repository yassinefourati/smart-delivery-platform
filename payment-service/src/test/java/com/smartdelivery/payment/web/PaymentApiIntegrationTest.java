package com.smartdelivery.payment.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.payment.event.EventEnvelope;
import com.smartdelivery.payment.event.KafkaTopics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import com.smartdelivery.payment.security.TestJwtIssuer;
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

import java.time.Duration;
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

    /**
     * Stands in for user-service: a real JWKS endpoint over HTTP, so these tests
     * exercise the same key-fetch-and-select path production does (ADR 007).
     */
    private static final TestJwtIssuer JWT_ISSUER = new TestJwtIssuer();

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
        return JWT_ISSUER.token(UUID.randomUUID(), role);
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

    // --- Phase 16: this service can verify a token but could never mint one (ADR 007) ---

    /**
     * The vulnerability Phase 16 closed, asserted from the outside: this token is signed
     * with the HMAC secret every service used to hold, and it claims SERVICE -- which on
     * this service is enough to charge and refund. Before this phase payment-service
     * would have accepted it, and could have minted it.
     */
    @Test
    void aTokenSignedWithTheOldSharedHmacSecretIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/payments/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + JWT_ISSUER.legacyHmacToken(UUID.randomUUID(), "SERVICE")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unsignedExpiredForeignAndUnknownKeyTokensAreAllRejected() throws Exception {
        UUID subject = UUID.randomUUID();
        for (String token : List.of(
                JWT_ISSUER.unsignedToken(subject, "SERVICE"),
                JWT_ISSUER.tokenSignedByAnUnpublishedKey(subject, "SERVICE"),
                JWT_ISSUER.expiredToken(subject, "SERVICE"),
                JWT_ISSUER.tokenFromAnotherIssuer(subject, "SERVICE"),
                JWT_ISSUER.tamperedToken(subject))) {
            mockMvc.perform(get("/api/v1/payments/{id}", UUID.randomUUID())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
    }
}
