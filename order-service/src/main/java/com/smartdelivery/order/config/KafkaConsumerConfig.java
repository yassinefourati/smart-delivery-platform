package com.smartdelivery.order.config;

import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka consumer retry/dead-letter policy -- distinct from, and not a substitute for,
 * the Resilience4j-based resilience added in Phase 11 for synchronous REST calls
 * (e.g. ProductServiceClient). This is specifically about what happens when a
 * listener method throws while processing a Kafka message.
 *
 * Builds on Spring Boot's own auto-configured {@link ConsumerFactory} (so every
 * {@code spring.kafka.consumer.*} property in application.yml still applies) and adds
 * a bounded retry + dead-letter policy: a message that fails is retried three times,
 * one second apart, and once exhausted, {@link DeadLetterPublishingRecoverer}
 * republishes it to "&lt;topic&gt;.DLT" instead of either blocking the partition
 * forever or silently dropping it (master brief section 15/17). This is deliberately
 * not infinite retry: an event whose handling keeps failing (a bug, a poison message)
 * would otherwise wedge that partition for every order behind it.
 */
@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        var backOff = new FixedBackOff(1000L, 3);
        return new DefaultErrorHandler(recoverer, backOff);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> kafkaConsumerFactory,
            DefaultErrorHandler kafkaErrorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, kafkaConsumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }
}
