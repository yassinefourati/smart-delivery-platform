package com.smartdelivery.order.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.order.service.OrderSagaOrchestrator;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * order-service consuming its own published event to kick off the saga
 * (OrderSagaOrchestrator), rather than starting it inline in the HTTP request that
 * created the order. This decouples "the order was created" (fast, synchronous) from
 * "reserve inventory and charge payment" (slower, and the whole point of the
 * asynchronous saga design in docs/order-flow.md), and it means saga-start failures
 * get the same bounded retry + dead-letter handling as every other consumer here
 * (KafkaConsumerConfig) for free, instead of needing bespoke retry logic.
 */
@Component
public class OrderSagaStartListener {

    private final ObjectMapper objectMapper;
    private final OrderSagaOrchestrator orchestrator;

    public OrderSagaStartListener(ObjectMapper objectMapper, OrderSagaOrchestrator orchestrator) {
        this.objectMapper = objectMapper;
        this.orchestrator = orchestrator;
    }

    @KafkaListener(topics = KafkaTopics.ORDER_CREATED)
    public void onOrderCreated(String message) throws JsonProcessingException {
        EventEnvelope envelope = objectMapper.readValue(message, EventEnvelope.class);
        OrderCreatedPayload payload = objectMapper.treeToValue(envelope.payload(), OrderCreatedPayload.class);
        orchestrator.startSaga(payload.orderId());
    }
}
