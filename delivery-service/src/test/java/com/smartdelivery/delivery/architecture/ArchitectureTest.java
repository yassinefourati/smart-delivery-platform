package com.smartdelivery.delivery.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The layering rules this service is built on, enforced rather than described (Phase 18).
 *
 * Everything here is a rule the codebase already follows; the point is that it keeps
 * following it. A layering convention held together by reviewer attention decays quietly,
 * one "just this once" at a time, and by the time it is visibly broken it is expensive to
 * put back. These are the three that would cost the most to lose.
 *
 * {@code importOptions} excludes test classes: tests legitimately reach across layers to
 * set up state, and holding them to production layering would only teach people to work
 * around the rules.
 */
@AnalyzeClasses(
        packages = "com.smartdelivery.delivery",
        importOptions = com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * A controller that reaches into a repository skips the service layer, and with it
     * every transaction boundary, authorization check and domain invariant that lives
     * there. It also tends not to look like a shortcut until three of them exist.
     */
    @ArchTest
    static final ArchRule controllersDoNotTouchRepositoriesDirectly =
            noClasses().that().resideInAPackage("..web..")
                    .should().dependOnClassesThat().resideInAPackage("..repository..")
                    .because("controllers go through the service layer, which owns transactions and authorization");

    /**
     * The domain is the part of this service that would survive the API being rewritten.
     * A domain class that knows about a request or response type has been quietly
     * repurposed as a serialization format, and then changing the API means changing the
     * model.
     */
    @ArchTest
    static final ArchRule domainDoesNotDependOnTheWebLayer =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("..web..", "..dto..")
                    .because("the domain model must not be shaped by what the API happens to expose");

    /**
     * Publishing goes through the transactional outbox (ADR 004), which only works if
     * nothing bypasses it. A service or controller holding a KafkaTemplate is exactly the
     * commit-then-publish gap Phase 8 closed, reintroduced.
     */
    @ArchTest
    static final ArchRule businessCodeDoesNotPublishToKafkaDirectly =
            noClasses().that().resideInAnyPackage("..domain..", "..service..", "..web..", "..repository..")
                    .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.kafka.core.KafkaTemplate")
                    .because("events are written to the outbox and sent from the event layer (ADR 004)");

    /**
     * The same rule stated from the other side, which is where it is actually enforced:
     * {@code event} is the only package that may touch Kafka at all.
     *
     * {@code config} is the one exemption, and a narrow one: {@code KafkaConsumerConfig}
     * hands a KafkaTemplate to Spring Kafka's DeadLetterPublishingRecoverer so a poison
     * message can be republished. That is Spring wiring, not this service deciding to
     * announce something -- which is the thing the rule exists to prevent.
     */
    @ArchTest
    static final ArchRule onlyTheEventAndConfigPackagesTouchKafka =
            noClasses().that().resideOutsideOfPackages("..event..", "..config..")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework.kafka..")
                    .because("Kafka is an implementation detail of the event layer");

    /**
     * Sharper than {@link #businessCodeDoesNotPublishToKafkaDirectly}, and only possible
     * since Phase 19: the outbox poller moved to {@code platform-starter}
     * (ADR 009), so <em>nothing in this service</em> has a legitimate reason to hold a
     * KafkaTemplate any more -- not even the event layer, which now only writes rows.
     * The single exemption is {@code KafkaConsumerConfig} handing one to Spring Kafka's
     * DeadLetterPublishingRecoverer so a poison message can be republished, which is
     * framework wiring rather than this service deciding to announce something.
     *
     * Stated as its own rule rather than by tightening the one above, because the two
     * fail for different reasons and the message matters when one does.
     */
    @ArchTest
    static final ArchRule nothingOutsideTheDeadLetterWiringHoldsAKafkaTemplate =
            noClasses().that().resideOutsideOfPackage("..config..")
                    .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.kafka.core.KafkaTemplate")
                    .because("since Phase 19 the outbox poller lives in platform-starter; publishing means writing an outbox row");
}
