package com.smartdelivery.notification.event;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.notification.notify.NotificationSender;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Real publish -> consume -> render -> log path against a real Kafka (Testcontainers).
 * No Postgres container here -- notification-service owns no data (see
 * docs/service-boundaries.md), so there's nothing to persist to verify against; a
 * Logback {@link ListAppender} attached to {@link NotificationSender}'s logger is the
 * observable side effect this test asserts on.
 */
@SpringBootTest
@Testcontainers
class NotificationEventListenerIntegrationTest {

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private ObjectMapper objectMapper;

    private KafkaProducer<String, String> testProducer;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        testProducer = new KafkaProducer<>(props);

        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(NotificationSender.class)).addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        testProducer.close();
        ((Logger) LoggerFactory.getLogger(NotificationSender.class)).detachAppender(logAppender);
    }

    private void publish(String topic, String key, String eventType, Object payload) throws Exception {
        var envelope = new EventEnvelope(
                UUID.randomUUID(), eventType, 1, Instant.now(), UUID.randomUUID(), "test-producer",
                objectMapper.valueToTree(payload));
        testProducer.send(new ProducerRecord<>(topic, key, objectMapper.writeValueAsString(envelope)))
                .get(10, TimeUnit.SECONDS);
    }

    private void assertLoggedEventually(String... expectedFragments) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(logAppender.list)
                        .anySatisfy(event -> {
                            String formatted = event.getFormattedMessage();
                            for (String fragment : expectedFragments) {
                                assertThat(formatted).contains(fragment);
                            }
                        }));
    }

    @Test
    void orderCreatedEventIsConsumedAndLoggedAsANotification() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var payload = new OrderCreatedPayload(orderId, userId, java.util.List.of(), new BigDecimal("19.98"));

        publish(KafkaTopics.ORDER_CREATED, orderId.toString(), "OrderCreated", payload);

        assertLoggedEventually(orderId.toString(), userId.toString());
    }

    @Test
    void paymentCompletedEventIsConsumedAndLoggedAsANotification() throws Exception {
        UUID orderId = UUID.randomUUID();
        var payload = new PaymentCompletedPayload(orderId, UUID.randomUUID(), new BigDecimal("42.00"));

        publish(KafkaTopics.PAYMENT_COMPLETED, orderId.toString(), "PaymentCompleted", payload);

        assertLoggedEventually(orderId.toString(), "42.00");
    }

    @Test
    void deliveryCompletedEventIsConsumedAndLoggedAsANotification() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        var payload = new DeliveryCompletedPayload(orderId, shipmentId, Instant.now());

        publish(KafkaTopics.DELIVERY_COMPLETED, orderId.toString(), "DeliveryCompleted", payload);

        assertLoggedEventually(orderId.toString(), shipmentId.toString());
    }
}
