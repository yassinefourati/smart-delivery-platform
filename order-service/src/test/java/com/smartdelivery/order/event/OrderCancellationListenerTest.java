package com.smartdelivery.order.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import com.smartdelivery.order.domain.OrderStatus;
import com.smartdelivery.order.service.OrderSagaOrchestrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderCancellationListenerTest {

    /**
     * Built the way Spring Boot builds the one the listener is actually injected with --
     * in particular with FAIL_ON_UNKNOWN_PROPERTIES off, which is what makes the
     * tolerant-reader test below mean anything.
     */
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    @Mock
    private OrderSagaOrchestrator orchestrator;

    private OrderCancellationListener listener() {
        return new OrderCancellationListener(objectMapper, orchestrator);
    }

    private String envelopeWith(Map<String, Object> payload) throws Exception {
        var envelope = new EventEnvelope(UUID.randomUUID(), "OrderCancelled", 1, Instant.now(),
                UUID.randomUUID(), "order-service", objectMapper.valueToTree(payload));
        return objectMapper.writeValueAsString(envelope);
    }

    @Test
    void compensatesUsingThePreviousStatusFromTheEvent() throws Exception {
        UUID orderId = UUID.randomUUID();
        String message = envelopeWith(Map.of(
                "orderId", orderId, "userId", UUID.randomUUID(),
                "reason", "Cancelled by customer", "previousStatus", "PAID"));

        listener().onOrderCancelled(message);

        // PAID means the money moved and the stock was already deducted -- a refund, not
        // a release. Reading that off the event is the whole reason it carries the field.
        verify(orchestrator).compensateCancellation(orderId, OrderStatus.PAID);
    }

    /**
     * An event published before Phase 17 has no previousStatus and no way to reconstruct
     * one -- and was already compensated inline when it was published, by the code this
     * listener replaced. Skipping is both the only option and the right one.
     */
    @Test
    void skipsAnEventWithNoPreviousStatusRatherThanGuessing() throws Exception {
        String message = envelopeWith(Map.of(
                "orderId", UUID.randomUUID(), "userId", UUID.randomUUID(), "reason", "Cancelled by customer"));

        listener().onOrderCancelled(message);

        verifyNoInteractions(orchestrator);
    }

    /** Unlike a missing value, an unrecognisable one is a real defect and belongs on the DLT. */
    @Test
    void throwsOnAnUnrecognisablePreviousStatusSoItReachesTheDeadLetterTopic() throws Exception {
        String message = envelopeWith(Map.of(
                "orderId", UUID.randomUUID(), "userId", UUID.randomUUID(),
                "reason", "Cancelled by customer", "previousStatus", "NOT_A_STATUS"));

        assertThatThrownBy(() -> listener().onOrderCancelled(message))
                .isInstanceOf(IllegalArgumentException.class);
        verify(orchestrator, never()).compensateCancellation(any(), any());
    }

    /** A payload the producer has since extended must not break this consumer -- ADR 002. */
    @Test
    void toleratesUnknownFieldsInThePayload() throws Exception {
        UUID orderId = UUID.randomUUID();
        String message = envelopeWith(Map.of(
                "orderId", orderId, "userId", UUID.randomUUID(), "reason", "Cancelled by customer",
                "previousStatus", "INVENTORY_RESERVED", "somethingAddedLater", "ignore me"));

        listener().onOrderCancelled(message);

        verify(orchestrator).compensateCancellation(orderId, OrderStatus.INVENTORY_RESERVED);
    }
}
