package com.smartdelivery.platform.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.Arrays;
import java.util.List;

/**
 * Turns the names of the topics a service publishes to into the {@link KafkaAdmin.NewTopics}
 * Spring Kafka creates at startup (Phase 23, ADR 015).
 *
 * The names stay in each service's own {@code KafkaTopics} class, next to its event
 * payloads: which topics a service owns is part of its event contract, and those stay
 * per service (ADR 009). Only the mechanics -- partitions, replicas, on or off -- are
 * shared, so every producer sizes its topics the same way.
 *
 * <pre>{@code
 * @Bean
 * KafkaAdmin.NewTopics declaredKafkaTopics(OwnedTopics topics) {
 *     return topics.declare(KafkaTopics.ORDER_CREATED, KafkaTopics.ORDER_CANCELLED);
 * }
 * }</pre>
 *
 * (Not {@code ownedTopics}: that is this bean's own name, and Boot refuses a second bean
 * definition under it -- the service would fail to start.)
 */
public class OwnedTopics {

    private final KafkaTopicProperties properties;

    public OwnedTopics(KafkaTopicProperties properties) {
        this.properties = properties;
    }

    /** The topics to declare, or none when {@code kafka.topics.create} is off. */
    public KafkaAdmin.NewTopics declare(String... names) {
        return new KafkaAdmin.NewTopics(topics(names).toArray(NewTopic[]::new));
    }

    /**
     * What {@link #declare} hands to KafkaAdmin, as a plain list: empty when creation is
     * off, otherwise one {@link NewTopic} per name with the configured partitions and, when
     * set, replication factor (left to the broker's default otherwise).
     */
    public List<NewTopic> topics(String... names) {
        if (!properties.create()) {
            return List.of();
        }
        return Arrays.stream(names)
                .map(name -> {
                    TopicBuilder builder = TopicBuilder.name(name).partitions(properties.partitions());
                    if (properties.replicas() > 0) {
                        builder.replicas(properties.replicas());
                    }
                    return builder.build();
                })
                .toList();
    }
}
