package com.smartdelivery.platform.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.platform.kafka.OwnedTopics;
import com.smartdelivery.platform.observability.CorrelationIdFilter;
import com.smartdelivery.platform.openapi.PlatformOpenApiConfiguration;
import com.smartdelivery.platform.outbox.OutboxCleanupJob;
import com.smartdelivery.platform.outbox.OutboxEventRepository;
import com.smartdelivery.platform.outbox.OutboxMetrics;
import com.smartdelivery.platform.outbox.OutboxPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The conditions, not the classes. Everything the starter contributes is reached through
 * an auto-configuration, so "is it wired where it should be, and absent where it should
 * not be" is a different question from "does the class work" -- and it is the one that
 * decides whether adding this dependency to a service is safe.
 */
class PlatformAutoConfigurationTest {

    // --- correlation id ---------------------------------------------------------------

    @Test
    void theCorrelationFilterIsRegisteredInAServletApplication() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformWebAutoConfiguration.class))
                .run(context -> assertThat(context).hasSingleBean(CorrelationIdFilter.class));
    }

    /**
     * api-gateway is reactive and has its own correlation filter, because a WebFlux
     * request is not pinned to one thread. If this condition ever loosened, the gateway
     * would get a servlet filter it cannot run.
     */
    @Test
    void theCorrelationFilterIsAbsentOutsideAServletApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformWebAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(CorrelationIdFilter.class));
    }

    // --- security ---------------------------------------------------------------------

    @Test
    void theResourceServerWiringIsProvided() {
        securityRunner().run(context -> {
            assertThat(context).hasSingleBean(JwtAuthenticationConverter.class);
            assertThat(context).hasSingleBean(com.smartdelivery.platform.security.JwtAuthenticationEntryPoint.class);
            assertThat(context).hasSingleBean(com.smartdelivery.platform.security.JwtAccessDeniedHandler.class);
        });
    }

    /** A service must always be able to take the wheel back; every bean is conditional. */
    @Test
    void aServiceCanReplaceTheConverterWithItsOwn() {
        JwtAuthenticationConverter own = new JwtAuthenticationConverter();
        securityRunner()
                .withBean(JwtAuthenticationConverter.class, () -> own)
                .run(context -> assertThat(context.getBean(JwtAuthenticationConverter.class)).isSameAs(own));
    }

    // --- outbox -----------------------------------------------------------------------

    @Test
    void theOutboxIsWiredWhenTheServiceHasBothAKafkaClientAndAPersistenceUnit() {
        outboxRunner().run(context -> {
            assertThat(context).hasSingleBean(OutboxPublisher.class);
            assertThat(context).hasSingleBean(OutboxCleanupJob.class);
            assertThat(context).hasSingleBean(OutboxMetrics.class);
        });
    }

    /**
     * The escape hatch for a service that acquires both dependencies for unrelated
     * reasons and has no outbox_events table: a property, not a code change.
     */
    @Test
    void theOutboxCanBeSwitchedOffByProperty() {
        outboxRunner()
                .withPropertyValues("platform.outbox.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(OutboxPublisher.class));
    }

    @Test
    void outboxTuningBindsFromProperties() {
        outboxRunner()
                .withPropertyValues("outbox.batch-size=7", "outbox.retention=2d")
                .run(context -> {
                    var properties = context.getBean(com.smartdelivery.platform.outbox.OutboxProperties.class);
                    assertThat(properties.batchSize()).isEqualTo(7);
                    assertThat(properties.retention()).hasDays(2);
                });
    }

    // --- kafka topics -----------------------------------------------------------------

    /**
     * Off unless asked for: every test context in the platform starts without a broker,
     * and a declared topic would make KafkaAdmin try to reach one at startup.
     */
    @Test
    void topicCreationIsOffByDefault() {
        kafkaRunner().run(context -> {
            OwnedTopics topics = context.getBean(OwnedTopics.class);
            assertThat(topics.topics("order.created")).isEmpty();
        });
    }

    @Test
    void topicSizingBindsFromTheEnvironmentNamesTheChartSets() {
        kafkaRunner()
                .withPropertyValues("kafka.topics.create=true", "kafka.topics.partitions=12",
                        "kafka.topics.replicas=3")
                .run(context -> assertThat(context.getBean(OwnedTopics.class).topics("order.created"))
                        .singleElement()
                        .satisfies(topic -> {
                            assertThat(topic.numPartitions()).isEqualTo(12);
                            assertThat(topic.replicationFactor()).isEqualTo((short) 3);
                        }));
    }

    /** A bad size stops the service at startup, not at the broker hours later. */
    @Test
    void aZeroPartitionCountFailsTheContext() {
        kafkaRunner()
                .withPropertyValues("kafka.topics.create=true", "kafka.topics.partitions=0")
                .run(context -> assertThat(context).hasFailed());
    }

    // --- openapi ----------------------------------------------------------------------

    @Test
    void theOpenApiDocumentAdvertisesTheGatewayAndABearerScheme() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformOpenApiAutoConfiguration.class))
                .withPropertyValues(
                        "spring.application.name=order-service",
                        "platform.openapi.title=Order Service API",
                        "platform.openapi.public-url=https://api.example.test")
                .run(context -> {
                    OpenAPI openApi = context.getBean(OpenAPI.class);
                    assertThat(openApi.getInfo().getTitle()).isEqualTo("Order Service API");
                    // One server, and it is the gateway: a document served through the
                    // gateway must not advertise the service's internal hostname, which
                    // resolves to nothing in the reader's browser.
                    assertThat(openApi.getServers()).singleElement()
                            .satisfies(server -> assertThat(server.getUrl()).isEqualTo("https://api.example.test"));
                    assertThat(openApi.getComponents().getSecuritySchemes())
                            .containsKey(PlatformOpenApiConfiguration.BEARER_SCHEME_NAME);
                });
    }

    @Test
    void theDocumentTitleFallsBackToTheApplicationName() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformOpenApiAutoConfiguration.class))
                .withPropertyValues("spring.application.name=payment-service")
                .run(context -> assertThat(context.getBean(OpenAPI.class).getInfo().getTitle())
                        .isEqualTo("payment-service"));
    }

    // --- helpers ----------------------------------------------------------------------

    private ApplicationContextRunner securityRunner() {
        return new ApplicationContextRunner()
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withConfiguration(AutoConfigurations.of(PlatformSecurityAutoConfiguration.class));
    }

    private ApplicationContextRunner kafkaRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformKafkaAutoConfiguration.class));
    }

    @SuppressWarnings("unchecked")
    private ApplicationContextRunner outboxRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(OutboxCollaborators.class)
                .withConfiguration(AutoConfigurations.of(PlatformOutboxAutoConfiguration.class));
    }

    /**
     * Stands in for what a real service brings: the repository Spring Data would have
     * created, a Kafka template, a transaction manager, and a meter registry.
     */
    @Configuration(proxyBeanMethods = false)
    static class OutboxCollaborators {

        @Bean
        OutboxEventRepository outboxEventRepository() {
            return mock(OutboxEventRepository.class);
        }

        @Bean
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate() {
            return mock(KafkaTemplate.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
