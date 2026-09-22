package com.smartdelivery.notification.config;

import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Same bounded retry + dead-letter policy as every other consumer in this platform
 * (see order-service's KafkaConsumerConfig for the full rationale): a listener that
 * throws is retried three times, one second apart, then dead-lettered to
 * {@code <topic>.DLT} rather than blocking the partition or being silently dropped.
 *
 * notification-service needs a {@link KafkaTemplate} only because
 * {@link DeadLetterPublishingRecoverer} uses one to publish to the {@code .DLT} topic
 * -- it never uses it to publish a real business event; see
 * docs/service-boundaries.md on why this is the one service in the platform that
 * publishes nothing of its own.
 */
@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    /** The dead-letter suffix this platform documents, monitors, and tests against. */
    static final String DLT_SUFFIX = ".DLT";

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        // The destination is named explicitly rather than left to Spring Kafka's default,
        // which is "<topic>-dlt" and not the "<topic>.DLT" this platform's docs, alerts,
        // and tests all refer to. Relying on the default meant poison messages piled up on
        // a topic nobody was watching -- found in Phase 16, when this test ran end to end
        // for the first time in a while.
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, record.partition()));
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
