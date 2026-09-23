package com.smartdelivery.delivery.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.delivery.event.EventEnvelope;
import com.smartdelivery.delivery.event.KafkaTopics;
import com.smartdelivery.delivery.event.PaymentCompletedPayload;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import com.smartdelivery.delivery.security.TestJwtIssuer;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end verification through the real HTTP + Spring Security filter chain and a
 * real Postgres + Kafka (Testcontainers): payment.completed in -> Shipment created ->
 * assign -> DeliveryAssigned out -> complete -> DeliveryCompleted out, plus ownership
 * enforcement on the agent-facing endpoints.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class DeliveryApiIntegrationTest {

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
        registry.add("outbox.poll-interval-ms", () -> "500");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private KafkaProducer<String, String> testProducer;
    private KafkaConsumer<String, String> testConsumer;

    @BeforeEach
    void setUp() {
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        testProducer = new KafkaProducer<>(producerProps);

        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "test-probe-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        testConsumer = new KafkaConsumer<>(consumerProps);
    }

    @AfterEach
    void tearDown() {
        testProducer.close();
        testConsumer.close();
    }

    private void publishPaymentCompleted(UUID orderId) throws Exception {
        var payload = new PaymentCompletedPayload(orderId, UUID.randomUUID(), new BigDecimal("50.00"));
        var envelope = new EventEnvelope(
                UUID.randomUUID(), "PaymentCompleted", 1, Instant.now(), UUID.randomUUID(), "payment-service",
                objectMapper.valueToTree(payload));
        testProducer.send(new ProducerRecord<>(KafkaTopics.PAYMENT_COMPLETED, orderId.toString(), objectMapper.writeValueAsString(envelope)))
                .get(10, TimeUnit.SECONDS);
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
        return JWT_ISSUER.token(userId, role);
    }

    private String adminToken() {
        return tokenFor(UUID.randomUUID(), "ADMIN");
    }

    @Test
    void paymentCompletedCreatesAShipmentAndTheAdminCanSeeIt() throws Exception {
        UUID orderId = UUID.randomUUID();
        publishPaymentCompleted(orderId);

        var record = consumeOne(KafkaTopics.SHIPMENT_CREATED, Duration.ofSeconds(20));
        var envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        assertThat(envelope.eventType()).isEqualTo("ShipmentCreated");
        assertThat(envelope.payload().get("orderId").asText()).isEqualTo(orderId.toString());

        mockMvc.perform(get("/api/v1/shipments/order/{orderId}", orderId)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void fullLifecycleAssignThenCompletePublishesBothEventsAndEnforcesAgentOwnership() throws Exception {
        UUID orderId = UUID.randomUUID();
        publishPaymentCompleted(orderId);
        consumeOne(KafkaTopics.SHIPMENT_CREATED, Duration.ofSeconds(20));

        String shipmentJson = mockMvc.perform(get("/api/v1/shipments/order/{orderId}", orderId)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID shipmentId = UUID.fromString(objectMapper.readTree(shipmentJson).get("id").asText());

        UUID agentUserId = UUID.randomUUID();
        String createAgentRequest = objectMapper.writeValueAsString(
                Map.of("userId", agentUserId.toString(), "name", "Jane Doe", "phone", "+1-555-0100"));
        String agentJson = mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createAgentRequest))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID agentId = UUID.fromString(objectMapper.readTree(agentJson).get("id").asText());

        String assignRequest = objectMapper.writeValueAsString(Map.of("agentId", agentId.toString()));
        String deliveryJson = mockMvc.perform(post("/api/v1/shipments/{id}/assign", shipmentId)
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignRequest))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andReturn().getResponse().getContentAsString();
        UUID deliveryId = UUID.fromString(objectMapper.readTree(deliveryJson).get("id").asText());

        var assignedRecord = consumeOne(KafkaTopics.DELIVERY_ASSIGNED, Duration.ofSeconds(20));
        var assignedEnvelope = objectMapper.readValue(assignedRecord.value(), EventEnvelope.class);
        assertThat(assignedEnvelope.eventType()).isEqualTo("DeliveryAssigned");

        String otherAgentToken = tokenFor(UUID.randomUUID(), "DELIVERY_AGENT");
        mockMvc.perform(post("/api/v1/deliveries/{id}/complete", deliveryId)
                        .header("Authorization", "Bearer " + otherAgentToken))
                .andExpect(status().isForbidden());

        String ownAgentToken = tokenFor(agentUserId, "DELIVERY_AGENT");
        mockMvc.perform(post("/api/v1/deliveries/{id}/complete", deliveryId)
                        .header("Authorization", "Bearer " + ownAgentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        var completedRecord = consumeOne(KafkaTopics.DELIVERY_COMPLETED, Duration.ofSeconds(20));
        var completedEnvelope = objectMapper.readValue(completedRecord.value(), EventEnvelope.class);
        assertThat(completedEnvelope.eventType()).isEqualTo("DeliveryCompleted");

        mockMvc.perform(get("/api/v1/deliveries/agent/{userId}", agentUserId)
                        .header("Authorization", "Bearer " + ownAgentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("COMPLETED"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mockMvc.perform(get("/api/v1/shipments/{id}", shipmentId)
                                .header("Authorization", "Bearer " + adminToken()))
                        .andExpect(jsonPath("$.status").value("DELIVERED")));
    }

    // --- Phase 16: this service can verify a token but could never mint one (ADR 007) ---

    /**
     * The vulnerability Phase 16 closed, asserted from the outside: this token is signed
     * with the HMAC secret every service used to hold, and it claims ADMIN.
     */
    @Test
    void aTokenSignedWithTheOldSharedHmacSecretIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/agents")
                        .header("Authorization", "Bearer " + JWT_ISSUER.legacyHmacToken(UUID.randomUUID(), "ADMIN")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unsignedExpiredForeignAndUnknownKeyTokensAreAllRejected() throws Exception {
        UUID subject = UUID.randomUUID();
        for (String token : List.of(
                JWT_ISSUER.unsignedToken(subject, "ADMIN"),
                JWT_ISSUER.tokenSignedByAnUnpublishedKey(subject, "ADMIN"),
                JWT_ISSUER.expiredToken(subject, "ADMIN"),
                JWT_ISSUER.tokenFromAnotherIssuer(subject, "ADMIN"),
                JWT_ISSUER.tamperedToken(subject))) {
            mockMvc.perform(get("/api/v1/agents").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
    }
}
