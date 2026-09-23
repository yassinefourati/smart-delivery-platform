package com.smartdelivery.inventory.event;

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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class InventoryEventPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private InventoryEventPublisher publisher() {
        return new InventoryEventPublisher(outboxEventRepository, objectMapper);
    }

    @Test
    void publishReservedWritesAPendingOutboxRowScopedToTheOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        var payload = new InventoryReservedPayload(UUID.randomUUID(), orderId, UUID.randomUUID(), 3);

        publisher().publishReserved(payload);

        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        OutboxEvent saved = outboxEventCaptor.getValue();

        assertThat(saved.getAggregateType()).isEqualTo("InventoryReservation");
        assertThat(saved.getAggregateId()).isEqualTo(orderId);
        assertThat(saved.getEventType()).isEqualTo("InventoryReserved");
        assertThat(saved.getTopic()).isEqualTo(KafkaTopics.INVENTORY_RESERVED);
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);

        EventEnvelope envelope = objectMapper.readValue(saved.getPayload(), EventEnvelope.class);
        assertThat(envelope.payload().get("orderId").asText()).isEqualTo(orderId.toString());
    }

    @Test
    void publishReleasedWritesAPendingOutboxRow() {
        UUID orderId = UUID.randomUUID();
        var payload = new InventoryReleasedPayload(UUID.randomUUID(), orderId, UUID.randomUUID(), 1);

        publisher().publishReleased(payload);

        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getTopic()).isEqualTo(KafkaTopics.INVENTORY_RELEASED);
    }

    @Test
    void publishFailedWritesAPendingOutboxRow() {
        UUID orderId = UUID.randomUUID();
        var payload = new InventoryFailedPayload(orderId, UUID.randomUUID(), 5, "INSUFFICIENT_STOCK");

        publisher().publishFailed(payload);

        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getTopic()).isEqualTo(KafkaTopics.INVENTORY_FAILED);
    }
}
