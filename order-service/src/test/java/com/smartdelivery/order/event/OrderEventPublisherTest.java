package com.smartdelivery.order.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.order.domain.Order;
import com.smartdelivery.order.domain.OrderStatus;
import com.smartdelivery.order.domain.OrderItem;
import com.smartdelivery.platform.outbox.OutboxEvent;
import com.smartdelivery.platform.outbox.OutboxEventRepository;
import com.smartdelivery.platform.outbox.OutboxStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderEventPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Captor
    private ArgumentCaptor<OutboxEvent> outboxEventCaptor;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private OrderEventPublisher publisher() {
        return new OrderEventPublisher(outboxEventRepository, objectMapper);
    }

    private Order orderWithOneItem() {
        Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), null, null);
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        order.addItem(new OrderItem(UUID.randomUUID(), "Widget", new BigDecimal("9.99"), 2));
        return order;
    }

    @Test
    void publishOrderCreatedWritesAPendingOutboxRowScopedToTheOrder() throws Exception {
        Order order = orderWithOneItem();

        publisher().publishOrderCreated(order);

        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        OutboxEvent saved = outboxEventCaptor.getValue();

        assertThat(saved.getAggregateType()).isEqualTo("Order");
        assertThat(saved.getAggregateId()).isEqualTo(order.getId());
        assertThat(saved.getEventType()).isEqualTo("OrderCreated");
        assertThat(saved.getTopic()).isEqualTo(KafkaTopics.ORDER_CREATED);
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);

        EventEnvelope envelope = objectMapper.readValue(saved.getPayload(), EventEnvelope.class);
        assertThat(envelope.eventType()).isEqualTo("OrderCreated");
        assertThat(envelope.payload().get("orderId").asText()).isEqualTo(order.getId().toString());
    }

    @Test
    void publishOrderCancelledWritesAPendingOutboxRowScopedToTheOrder() throws Exception {
        Order order = orderWithOneItem();

        publisher().publishOrderCancelled(order, OrderStatus.PAID);

        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        OutboxEvent saved = outboxEventCaptor.getValue();

        assertThat(saved.getAggregateId()).isEqualTo(order.getId());
        assertThat(saved.getEventType()).isEqualTo("OrderCancelled");
        assertThat(saved.getTopic()).isEqualTo(KafkaTopics.ORDER_CANCELLED);
    }

    /**
     * The field compensation decides on. Without it the consumer cannot tell "release a
     * reservation" from "refund a payment" -- by then the order reads back as CANCELLED.
     */
    @Test
    void publishOrderCancelledCarriesThePreviousStatus() throws Exception {
        Order order = orderWithOneItem();

        publisher().publishOrderCancelled(order, OrderStatus.PAYMENT_PENDING);

        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        EventEnvelope envelope = objectMapper.readValue(outboxEventCaptor.getValue().getPayload(), EventEnvelope.class);
        assertThat(envelope.payload().get("previousStatus").asText()).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    void publishOrderFailedWritesAPendingOutboxRowOnItsOwnTopic() throws Exception {
        Order order = orderWithOneItem();

        publisher().publishOrderFailed(order, OrderStatus.INVENTORY_RESERVED, "Saga exhausted its retries");

        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        OutboxEvent saved = outboxEventCaptor.getValue();

        assertThat(saved.getAggregateId()).isEqualTo(order.getId());
        assertThat(saved.getEventType()).isEqualTo("OrderFailed");
        assertThat(saved.getTopic()).isEqualTo(KafkaTopics.ORDER_FAILED);

        EventEnvelope envelope = objectMapper.readValue(saved.getPayload(), EventEnvelope.class);
        assertThat(envelope.payload().get("previousStatus").asText()).isEqualTo("INVENTORY_RESERVED");
        assertThat(envelope.payload().get("reason").asText()).isEqualTo("Saga exhausted its retries");
    }
}
