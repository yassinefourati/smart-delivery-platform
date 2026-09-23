package com.smartdelivery.payment.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.platform.outbox.OutboxEvent;
import com.smartdelivery.platform.outbox.OutboxEventRepository;
import com.smartdelivery.platform.outbox.OutboxStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentEventPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private PaymentEventPublisher publisher() {
        return new PaymentEventPublisher(outboxEventRepository, objectMapper);
    }

    @Test
    void publishCompletedWritesAPendingOutboxRowScopedToTheOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        var payload = new PaymentCompletedPayload(orderId, UUID.randomUUID(), new BigDecimal("50.00"));

        publisher().publishCompleted(payload);

        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        OutboxEvent saved = outboxEventCaptor.getValue();

        assertThat(saved.getAggregateType()).isEqualTo("Payment");
        assertThat(saved.getAggregateId()).isEqualTo(orderId);
        assertThat(saved.getEventType()).isEqualTo("PaymentCompleted");
        assertThat(saved.getTopic()).isEqualTo(KafkaTopics.PAYMENT_COMPLETED);
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);

        EventEnvelope envelope = objectMapper.readValue(saved.getPayload(), EventEnvelope.class);
        assertThat(envelope.payload().get("orderId").asText()).isEqualTo(orderId.toString());
    }

    @Test
    void publishFailedWritesAPendingOutboxRow() {
        UUID orderId = UUID.randomUUID();
        var payload = new PaymentFailedPayload(orderId, "Payment declined");

        publisher().publishFailed(payload);

        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getTopic()).isEqualTo(KafkaTopics.PAYMENT_FAILED);
    }
}
