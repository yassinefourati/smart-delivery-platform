package com.smartdelivery.order.event;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventTest {

    private static final Duration INITIAL = Duration.ofSeconds(2);
    private static final Duration MAX = Duration.ofMinutes(5);

    private OutboxEvent event() {
        return new OutboxEvent("Order", UUID.randomUUID(), "OrderCreated", KafkaTopics.ORDER_CREATED, "{}");
    }

    @Test
    void aNewEventIsEligibleImmediately() {
        assertThat(event().getNextAttemptAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void backoffDoublesPerAttempt() {
        assertThat(OutboxEvent.backoffFor(1, INITIAL, MAX)).isEqualTo(Duration.ofSeconds(2));
        assertThat(OutboxEvent.backoffFor(2, INITIAL, MAX)).isEqualTo(Duration.ofSeconds(4));
        assertThat(OutboxEvent.backoffFor(3, INITIAL, MAX)).isEqualTo(Duration.ofSeconds(8));
        assertThat(OutboxEvent.backoffFor(4, INITIAL, MAX)).isEqualTo(Duration.ofSeconds(16));
    }

    @Test
    void backoffIsClampedToTheConfiguredMaximum() {
        assertThat(OutboxEvent.backoffFor(20, INITIAL, MAX)).isEqualTo(MAX);
        // The retry count is unbounded by design (ADR 004), so the arithmetic behind the
        // cap has to stay well-defined for an attempt count no realistic row will reach.
        assertThat(OutboxEvent.backoffFor(Integer.MAX_VALUE, INITIAL, MAX)).isEqualTo(MAX);
    }

    @Test
    void aFailedAttemptStaysPendingAndSchedulesTheNextOne() {
        OutboxEvent event = event();
        Instant before = Instant.now();

        event.recordFailedAttempt("broker unavailable", INITIAL, MAX);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("broker unavailable");
        assertThat(event.getNextAttemptAt()).isAfterOrEqualTo(before.plus(INITIAL));
    }

    @Test
    void successiveFailuresPushTheNextAttemptFurtherOut() {
        OutboxEvent event = event();
        event.recordFailedAttempt("first", INITIAL, MAX);
        Instant afterFirst = event.getNextAttemptAt();

        Instant beforeSecond = Instant.now();
        event.recordFailedAttempt("second", INITIAL, MAX);

        assertThat(event.getAttempts()).isEqualTo(2);
        assertThat(event.getNextAttemptAt()).isAfter(afterFirst);
        assertThat(event.getNextAttemptAt()).isAfterOrEqualTo(beforeSecond.plus(INITIAL.multipliedBy(2)));
    }

    @Test
    void publishingClearsNothingButRecordsWhenItHappened() {
        OutboxEvent event = event();

        event.markPublished();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isNotNull();
    }
}
