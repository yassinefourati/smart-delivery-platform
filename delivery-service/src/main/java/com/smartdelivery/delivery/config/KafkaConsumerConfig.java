package com.smartdelivery.delivery.config;

import org.apache.kafka.common.TopicPartition;
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
 * Same bounded retry + dead-letter policy as order-service's KafkaConsumerConfig (see
 * that class's Javadoc for the full rationale): a listener that throws is retried three
 * times, one second apart, then dead-lettered to {@code <topic>.DLT} rather than
 * blocking the partition or being silently dropped.
 */
@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    /** The dead-letter suffix this platform documents, monitors, and tests against. */
    static final String DLT_SUFFIX = ".DLT";

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        // Named explicitly, as in order- and notification-service. Spring Kafka's default
        // resolver publishes to "<topic>-dlt" -- not the "<topic>.DLT" docs/kafka-events.md
        // documents and operators watch -- AND mirrors the source partition number, which
        // breaks as soon as the source topic has more partitions than its dead-letter topic
        // (Phase 23, ADR 015). This service was missed when Phase 16 fixed the other two.
        // Partition -1 lets the producer choose from the key.
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
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
