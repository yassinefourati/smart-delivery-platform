package com.smartdelivery.notification.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * notification-service's layering rules, which are not the other services' (Phase 18).
 *
 * It has no {@code web}, {@code domain}, {@code repository} or {@code service} package to
 * write rules about, and that is the point rather than an omission: it owns no data,
 * exposes no API, and publishes no events of its own -- see docs/service-boundaries.md.
 * Copying the other six services' rules here would have produced four checks that matched
 * no classes and therefore proved nothing.
 *
 * What is worth enforcing is the shape itself. Each of these is a boundary that would be
 * easy to cross by accident the first time someone needs "just a small endpoint" or "just
 * a little table", and hard to walk back once something depends on it.
 */
@AnalyzeClasses(
        packages = "com.smartdelivery.notification",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * No REST API. Nothing calls notification-service; it only reacts to events. An
     * endpoint here would be the first thing that made it callable, and therefore the
     * first thing that could make it a synchronous dependency of something else.
     */
    @ArchTest
    static final ArchRule exposesNoHttpApi =
            noClasses().should().dependOnClassesThat()
                    .resideInAnyPackage("org.springframework.web.bind.annotation..", "org.springframework.web.servlet..")
                    .because("notification-service is event-driven only and owns no API (docs/service-boundaries.md)");

    /**
     * No database. It renders events into messages and forgets them; the moment it has a
     * table it has state to keep consistent, migrate, and reason about on redelivery --
     * which is precisely the cost it is designed not to pay (see NotificationSender).
     */
    @ArchTest
    static final ArchRule ownsNoDatabase =
            noClasses().should().dependOnClassesThat()
                    .resideInAnyPackage("jakarta.persistence..", "org.springframework.data.repository..")
                    .because("notification-service owns no data (docs/service-boundaries.md)");

    /**
     * It consumes ten topics and publishes none. The one KafkaTemplate it holds is in
     * {@code config}, where Spring Kafka's DeadLetterPublishingRecoverer needs one to
     * republish a poison message -- infrastructure, not this service announcing anything.
     */
    @ArchTest
    static final ArchRule publishesNothingOfItsOwn =
            noClasses().that().resideOutsideOfPackage("..config..")
                    .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.kafka.core.KafkaTemplate")
                    .because("notification-service is the one service in the platform that publishes no events");

    /**
     * The mock boundary stays a boundary: only {@code notify} knows how a message is
     * actually delivered, so swapping the logging stub for a real email or SMS provider
     * is a change in one package.
     */
    @ArchTest
    static final ArchRule listenersDoNotKnowHowMessagesAreSent =
            noClasses().that().resideInAPackage("..event..")
                    .should().dependOnClassesThat().haveSimpleNameEndingWith("NotificationSenderImpl")
                    .because("listeners depend on the NotificationSender abstraction, not on a delivery mechanism");
}
