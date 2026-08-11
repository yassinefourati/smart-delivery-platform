package com.smartdelivery.payment.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxPublisher publisher() {
        return new OutboxPublisher(outboxEventRepository, kafkaTemplate);
    }

    private OutboxEvent pendingEvent() {
        OutboxEvent event = new OutboxEvent("Payment", UUID.randomUUID(), "PaymentCompleted", KafkaTopics.PAYMENT_COMPLETED, "{}");
        ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
        return event;
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishPendingMarksARowPublishedOnASuccessfulSend() {
        OutboxEvent event = pendingEvent();
        when(outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING, PageRequest.of(0, 50)))
                .thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher().publishPending();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        verify(outboxEventRepository).save(event);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishPendingLeavesARowPendingAndRecordsTheErrorWhenTheSendFails() {
        OutboxEvent event = pendingEvent();
        when(outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING, PageRequest.of(0, 50)))
                .thenReturn(List.of(event));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker unavailable"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(failed);

        publisher().publishPending();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);
        verify(outboxEventRepository).save(event);
    }
}
