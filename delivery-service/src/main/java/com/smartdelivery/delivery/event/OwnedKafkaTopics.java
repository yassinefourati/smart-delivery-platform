package com.smartdelivery.delivery.event;

import com.smartdelivery.platform.kafka.OwnedTopics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * The topics delivery-service publishes to, and therefore owns (Phase 23, ADR 015): declared
 * with the platform's partition count when {@code kafka.topics.create} is on, so consumer
 * parallelism is not capped at one thread by a broker default of one partition. Topics
 * this service only consumes are declared by the service that publishes them.
 */
@Configuration
public class OwnedKafkaTopics {

    @Bean
    public KafkaAdmin.NewTopics declaredKafkaTopics(OwnedTopics topics) {
        return topics.declare(KafkaTopics.SHIPMENT_CREATED, KafkaTopics.DELIVERY_ASSIGNED, KafkaTopics.DELIVERY_COMPLETED);
    }
}
