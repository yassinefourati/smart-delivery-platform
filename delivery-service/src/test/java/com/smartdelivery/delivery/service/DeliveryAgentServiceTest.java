package com.smartdelivery.delivery.service;

import com.smartdelivery.delivery.domain.DeliveryAgent;
import com.smartdelivery.delivery.exception.DeliveryAgentNotFoundException;
import com.smartdelivery.delivery.exception.DuplicateAgentUserIdException;
import com.smartdelivery.delivery.repository.DeliveryAgentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryAgentServiceTest {

    @Mock
    private DeliveryAgentRepository deliveryAgentRepository;

    private DeliveryAgentService service() {
        return new DeliveryAgentService(deliveryAgentRepository);
    }

    @Test
    void createPersistsANewAgent() {
        UUID userId = UUID.randomUUID();
        when(deliveryAgentRepository.existsByUserId(userId)).thenReturn(false);
        when(deliveryAgentRepository.saveAndFlush(any(DeliveryAgent.class))).thenAnswer(inv -> inv.getArgument(0));

        DeliveryAgent result = service().create(userId, "Jane Doe", "+1-555-0100");

        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getName()).isEqualTo("Jane Doe");
    }

    @Test
    void createRejectsADuplicateUserId() {
        UUID userId = UUID.randomUUID();
        when(deliveryAgentRepository.existsByUserId(userId)).thenReturn(true);

        assertThatThrownBy(() -> service().create(userId, "Jane Doe", "+1-555-0100"))
                .isInstanceOf(DuplicateAgentUserIdException.class);
    }

    @Test
    void getByIdThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(deliveryAgentRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getById(id)).isInstanceOf(DeliveryAgentNotFoundException.class);
    }
}
