package com.smartdelivery.delivery.event;

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

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DeliveryEventPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private DeliveryEventPublisher publisher() {
        return new DeliveryEventPublisher(outboxEventRepository, objectMapper);
    }

    @Test
    void publishShipmentCreatedWritesAPendingOutboxRowScopedToTheOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        var payload = new ShipmentCreatedPayload(orderId, UUID.randomUUID());

        publisher().publishShipmentCreated(payload);

        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        OutboxEvent saved = outboxEventCaptor.getValue();

        assertThat(saved.getAggregateType()).isEqualTo("Shipment");
        assertThat(saved.getAggregateId()).isEqualTo(orderId);
        assertThat(saved.getEventType()).isEqualTo("ShipmentCreated");
        assertThat(saved.getTopic()).isEqualTo(KafkaTopics.SHIPMENT_CREATED);
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);

        EventEnvelope envelope = objectMapper.readValue(saved.getPayload(), EventEnvelope.class);
        assertThat(envelope.payload().get("orderId").asText()).isEqualTo(orderId.toString());
    }

    @Test
    void publishDeliveryAssignedWritesAPendingOutboxRow() {
        UUID orderId = UUID.randomUUID();
        var payload = new DeliveryAssignedPayload(orderId, UUID.randomUUID(), UUID.randomUUID());

        publisher().publishDeliveryAssigned(payload);

        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getTopic()).isEqualTo(KafkaTopics.DELIVERY_ASSIGNED);
    }

    @Test
    void publishDeliveryCompletedWritesAPendingOutboxRow() {
        UUID orderId = UUID.randomUUID();
        var payload = new DeliveryCompletedPayload(orderId, UUID.randomUUID(), Instant.now());

        publisher().publishDeliveryCompleted(payload);

        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        assertThat(outboxEventCaptor.getValue().getTopic()).isEqualTo(KafkaTopics.DELIVERY_COMPLETED);
    }
}
