package com.smartdelivery.platform.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How the topics a service publishes to are created (Phase 23, ADR 015).
 *
 * Partitions are the ceiling on consumer parallelism: a consumer group can never have more
 * active consumers on a topic than it has partitions, however many pods or listener threads
 * it runs. A broker's default of one partition therefore serialises the whole saga through
 * one thread per topic, platform-wide. Every event is keyed by its aggregate id (the
 * outbox publisher sends the order id as the key), so one order's events still land on one
 * partition, in order, whatever this number is.
 *
 * Off by default, so nothing -- in particular no test context without a broker -- waits on
 * an admin connection at startup. Compose and the Helm chart turn it on
 * ({@code KAFKA_TOPICS_CREATE=true}). A deployment whose topics are owned by
 * infrastructure-as-code (Strimzi {@code KafkaTopic}, Terraform for MSK) leaves it off and
 * declares the same partition count there.
 *
 * @param create     declare this service's topics through Spring Kafka's KafkaAdmin at
 *                   startup: missing topics are created, and an existing topic with FEWER
 *                   partitions is increased to this count (never decreased -- Kafka cannot).
 * @param partitions partitions per topic. Size it to the most consumer threads any one group
 *                   will run: replicas x {@code spring.kafka.listener.concurrency}.
 * @param replicas   replication factor; -1 leaves it to the broker's default, which is
 *                   what a single-broker Compose stack needs.
 */
@ConfigurationProperties(prefix = "kafka.topics")
public record KafkaTopicProperties(
        @DefaultValue("false") boolean create,
        @DefaultValue("6") int partitions,
        @DefaultValue("-1") int replicas) {

    public KafkaTopicProperties {
        if (partitions < 1) {
            throw new IllegalArgumentException("kafka.topics.partitions must be at least 1, got " + partitions);
        }
        if (replicas == 0 || replicas < -1) {
            throw new IllegalArgumentException("kafka.topics.replicas must be -1 (broker default) or at least 1, got " + replicas);
        }
    }
}
