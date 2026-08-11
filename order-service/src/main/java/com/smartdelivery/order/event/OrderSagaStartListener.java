package com.smartdelivery.order.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.order.service.OrderSagaOrchestrator;
import org.slf4j.MDC;
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
 *
 * Sets the consumed event's {@code correlationId} into MDC for the duration of
 * processing (see CorrelationIdFilter, docs/observability.md) -- so the REST calls
 * OrderSagaOrchestrator makes, and any outbox row a saga step writes, carry the same id
 * as the HTTP request that originally created the order, rather than a fresh unrelated
 * one just because this step happens to run off a Kafka consumer thread.
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
        MDC.put("correlationId", envelope.correlationId().toString());
        try {
            orchestrator.startSaga(payload.orderId());
        } finally {
            MDC.remove("correlationId");
        }
    }
}
