package com.smartdelivery.platform.autoconfigure;

import com.smartdelivery.platform.outbox.OutboxCleanupJob;
import com.smartdelivery.platform.outbox.OutboxEventRepository;
import com.smartdelivery.platform.outbox.OutboxMetrics;
import com.smartdelivery.platform.outbox.OutboxProperties;
import com.smartdelivery.platform.outbox.OutboxPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires the transactional outbox (ADR 004, ADR 006) into any service that has both a JPA
 * persistence unit and a Kafka client. Before Phase 19 the seven classes behind it were
 * copied byte-for-byte into order-, inventory-, payment-, and delivery-service, which
 * ADR 006 recorded at the time as the single strongest argument for a shared module.
 *
 * <p>The class conditions are precise enough that no service has to opt in or out by
 * hand: user- and product-service have JPA but no Kafka and get nothing;
 * notification-service has Kafka but no database and gets nothing; the four services
 * that publish events get the outbox. {@code platform.outbox.enabled=false} exists for
 * the case the conditions get it wrong -- a service that acquires both dependencies for
 * unrelated reasons and has no {@code outbox_events} table -- so the answer to that is a
 * property rather than a code change.
 *
 * <p>The schema is not shared. Each service still creates {@code outbox_events} in its own
 * Flyway migrations, because the table lives in that service's own database (ADR 001) and
 * a shared migration would be one module writing DDL into seven others' schemas. What
 * moved here is the Java; what stays there is the ownership of the data.
 */
@AutoConfiguration(before = {HibernateJpaAutoConfiguration.class, JpaRepositoriesAutoConfiguration.class})
@ConditionalOnClass({KafkaTemplate.class, EntityManagerFactory.class})
@ConditionalOnProperty(prefix = "platform.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OutboxProperties.class)
@Import(PlatformOutboxAutoConfiguration.OutboxPackageRegistrar.class)
public class PlatformOutboxAutoConfiguration {

    /**
     * Makes {@code com.smartdelivery.platform.outbox} visible to entity scanning and to
     * Spring Data repository scanning, which otherwise only ever look at the packages
     * beneath the service's own {@code @SpringBootApplication}.
     *
     * <p>The obvious alternatives are both wrong here. {@code @EntityScan} /
     * {@code @EnableJpaRepositories} on this class would <em>replace</em> Boot's
     * auto-configured scanning rather than add to it -- declaring
     * {@code @EnableJpaRepositories} anywhere makes {@code JpaRepositoriesAutoConfiguration}
     * back off entirely, so each service's own repositories would silently stop being
     * found. Appending to {@link AutoConfigurationPackages} instead leaves the service's
     * packages exactly as they were and adds one more, which is precisely the intent.
     * The {@code before = ...} on the auto-configuration is what guarantees this registrar
     * has run by the time either consumer reads that list.
     */
    static class OutboxPackageRegistrar implements ImportBeanDefinitionRegistrar {

        @Override
        public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
            AutoConfigurationPackages.register(registry, OutboxEventRepository.class.getPackageName());
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxMetrics outboxMetrics(MeterRegistry meterRegistry, OutboxEventRepository outboxEventRepository) {
        return new OutboxMetrics(meterRegistry, outboxEventRepository);
    }

    /**
     * {@code @Scheduled} on {@link OutboxPublisher#publishPending()} still applies: the
     * annotation is processed for any singleton, however it was defined, as long as the
     * service switches scheduling on. Every service that gets this auto-configuration
     * already carries {@code @EnableScheduling} on its application class.
     */
    @Bean
    @ConditionalOnMissingBean
    public OutboxPublisher outboxPublisher(OutboxEventRepository outboxEventRepository,
                                           KafkaTemplate<String, String> kafkaTemplate,
                                           OutboxProperties properties,
                                           OutboxMetrics metrics,
                                           PlatformTransactionManager transactionManager) {
        return new OutboxPublisher(outboxEventRepository, kafkaTemplate, properties, metrics, transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxCleanupJob outboxCleanupJob(OutboxEventRepository outboxEventRepository,
                                             OutboxProperties properties,
                                             OutboxMetrics metrics,
                                             PlatformTransactionManager transactionManager) {
        return new OutboxCleanupJob(outboxEventRepository, properties, metrics, transactionManager);
    }
}
