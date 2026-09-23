package com.smartdelivery.platform.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OwnedTopicsTest {

    @Test
    void declaresEveryNamedTopicWithTheConfiguredPartitionCount() {
        var topics = new OwnedTopics(new KafkaTopicProperties(true, 6, -1)).topics("order.created", "order.failed");

        assertThat(topics).extracting(NewTopic::name).containsExactly("order.created", "order.failed");
        assertThat(topics).allSatisfy(t -> assertThat(t.numPartitions()).isEqualTo(6));
        // -1: the broker's default replication factor, which a single-broker stack needs.
        assertThat(topics).allSatisfy(t -> assertThat(t.replicationFactor()).isEqualTo((short) -1));
    }

    @Test
    void anExplicitReplicationFactorIsPassedThrough() {
        var topics = new OwnedTopics(new KafkaTopicProperties(true, 12, 3)).topics("payment.completed");

        NewTopic topic = topics.get(0);
        assertThat(topic.numPartitions()).isEqualTo(12);
        assertThat(Optional.of(topic.replicationFactor())).contains((short) 3);
    }

    /** Off: nothing is declared, so KafkaAdmin has no reason to open an admin connection. */
    @Test
    void declaresNothingWhenCreationIsOff() {
        var ownedTopics = new OwnedTopics(new KafkaTopicProperties(false, 6, -1));

        assertThat(ownedTopics.topics("order.created")).isEmpty();
        assertThat(ownedTopics.declare("order.created")).isNotNull();
    }

    @Test
    void rejectsSizesKafkaWouldRefuseAnyway() {
        assertThatThrownBy(() -> new KafkaTopicProperties(true, 0, -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("partitions");
        assertThatThrownBy(() -> new KafkaTopicProperties(true, 6, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("replicas");
    }
}
