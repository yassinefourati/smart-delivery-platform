package com.smartdelivery.platform.autoconfigure;

import com.smartdelivery.platform.kafka.KafkaTopicProperties;
import com.smartdelivery.platform.kafka.OwnedTopics;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Topic declaration for any service with a Kafka client (Phase 23, ADR 015). It only
 * provides {@link OwnedTopics}; a service declares nothing until it asks for its own
 * topics with it, so notification-service -- a consumer that owns no topic -- gets a bean
 * it never uses and nothing else.
 */
@AutoConfiguration
@ConditionalOnClass(KafkaAdmin.class)
@EnableConfigurationProperties(KafkaTopicProperties.class)
public class PlatformKafkaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OwnedTopics ownedTopics(KafkaTopicProperties properties) {
        return new OwnedTopics(properties);
    }
}
