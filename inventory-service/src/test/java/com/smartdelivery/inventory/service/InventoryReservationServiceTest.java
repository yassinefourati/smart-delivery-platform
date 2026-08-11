package com.smartdelivery.inventory.service;

import com.smartdelivery.inventory.domain.Inventory;
import com.smartdelivery.inventory.domain.InventoryReservation;
import com.smartdelivery.inventory.domain.Warehouse;
import com.smartdelivery.inventory.event.InventoryEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryReservationServiceTest {

    @Mock
    private InventoryReservationOperations operations;

    @Mock
    private InventoryEventPublisher eventPublisher;

    private InventoryReservationService service() {
        return new InventoryReservationService(operations, eventPublisher);
    }

    private InventoryReservation reservation() {
        Warehouse warehouse = new Warehouse("Main", "Somewhere");
        Inventory inventory = new Inventory(UUID.randomUUID(), warehouse, 10);
        InventoryReservation reservation = new InventoryReservation(inventory, UUID.randomUUID(), UUID.randomUUID(), 1);
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        return reservation;
    }

    @Test
    void reserveSucceedsOnFirstAttemptWithoutRetrying() {
        InventoryReservationService service = service();
        InventoryReservation expected = reservation();
        UUID orderId = expected.getOrderId();
        UUID productId = expected.getProductId();
        when(operations.reserveAttempt(orderId, productId, 1)).thenReturn(expected);

        InventoryReservation result = service.reserve(orderId, productId, 1);

        assertThat(result).isSameAs(expected);
        verify(operations, times(1)).reserveAttempt(orderId, productId, 1);
    }

    @Test
    void reserveRetriesOnOptimisticLockConflictAndSucceedsOnceTheConflictClears() {
        InventoryReservationService service = service();
        InventoryReservation expected = reservation();
        UUID orderId = expected.getOrderId();
        UUID productId = expected.getProductId();

        when(operations.reserveAttempt(orderId, productId, 1))
                .thenThrow(new ObjectOptimisticLockingFailureException(Inventory.class, "id"))
                .thenThrow(new ObjectOptimisticLockingFailureException(Inventory.class, "id"))
                .thenReturn(expected);

        InventoryReservation result = service.reserve(orderId, productId, 1);

        assertThat(result).isSameAs(expected);
        verify(operations, times(3)).reserveAttempt(orderId, productId, 1);
    }

    @Test
    void reserveGivesUpAfterExhaustingRetriesAndSurfacesTheConflict() {
        InventoryReservationService service = service();
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        when(operations.reserveAttempt(orderId, productId, 1))
                .thenThrow(new ObjectOptimisticLockingFailureException(Inventory.class, "id"));

        assertThatThrownBy(() -> service.reserve(orderId, productId, 1))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        verify(operations, times(5)).reserveAttempt(orderId, productId, 1);
    }

    @Test
    void releaseDelegatesToOperations() {
        InventoryReservationService service = service();
        InventoryReservation expected = reservation();
        UUID orderId = expected.getOrderId();
        UUID productId = expected.getProductId();
        when(operations.releaseAttempt(orderId, productId)).thenReturn(expected);

        InventoryReservation result = service.release(orderId, productId);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void deductDelegatesToOperations() {
        InventoryReservationService service = service();
        InventoryReservation expected = reservation();
        UUID orderId = expected.getOrderId();
        UUID productId = expected.getProductId();
        when(operations.deductAttempt(orderId, productId)).thenReturn(expected);

        InventoryReservation result = service.deduct(orderId, productId);

        assertThat(result).isSameAs(expected);
    }
}
