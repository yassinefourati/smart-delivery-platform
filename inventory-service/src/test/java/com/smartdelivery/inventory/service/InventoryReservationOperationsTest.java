package com.smartdelivery.inventory.service;

import com.smartdelivery.inventory.domain.Inventory;
import com.smartdelivery.inventory.domain.InventoryReservation;
import com.smartdelivery.inventory.domain.Warehouse;
import com.smartdelivery.inventory.event.InventoryEventPublisher;
import com.smartdelivery.inventory.event.InventoryReleasedPayload;
import com.smartdelivery.inventory.event.InventoryReservedPayload;
import com.smartdelivery.inventory.exception.InsufficientStockException;
import com.smartdelivery.inventory.repository.InventoryRepository;
import com.smartdelivery.inventory.repository.InventoryReservationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the Phase 8 outbox integration specifically: a real state change publishes
 * exactly once, and the idempotent short-circuit paths (an already-existing reservation,
 * an already-released reservation) do not re-announce an outcome that was already
 * announced the first time. See InventoryReservationOperations' class Javadoc.
 */
@ExtendWith(MockitoExtension.class)
class InventoryReservationOperationsTest {

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private InventoryReservationRepository reservationRepository;

    @Mock
    private InventoryEventPublisher eventPublisher;

    private InventoryReservationOperations operations() {
        return new InventoryReservationOperations(inventoryRepository, reservationRepository, eventPublisher);
    }

    private Inventory inventoryWithStock(UUID productId, int available) {
        Warehouse warehouse = new Warehouse("Main", "Somewhere");
        Inventory inventory = new Inventory(productId, warehouse, available);
        ReflectionTestUtils.setField(inventory, "id", UUID.randomUUID());
        return inventory;
    }

    @Test
    void reserveAttemptPublishesReservedOnceOnANewReservation() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Inventory inventory = inventoryWithStock(productId, 10);
        when(reservationRepository.findByOrderIdAndProductId(orderId, productId)).thenReturn(Optional.empty());
        when(inventoryRepository.findByProductIdAndAvailableQuantityGreaterThanEqualOrderByAvailableQuantityDesc(productId, 2))
                .thenReturn(List.of(inventory));
        when(reservationRepository.saveAndFlush(any(InventoryReservation.class))).thenAnswer(inv -> inv.getArgument(0));

        InventoryReservation result = operations().reserveAttempt(orderId, productId, 2);

        assertThat(result.getOrderId()).isEqualTo(orderId);
        verify(eventPublisher).publishReserved(new InventoryReservedPayload(result.getId(), orderId, productId, 2));
    }

    @Test
    void reserveAttemptDoesNotRepublishForAnAlreadyExistingReservation() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Inventory inventory = inventoryWithStock(productId, 10);
        InventoryReservation existing = new InventoryReservation(inventory, orderId, productId, 2);
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        when(reservationRepository.findByOrderIdAndProductId(orderId, productId)).thenReturn(Optional.of(existing));

        InventoryReservation result = operations().reserveAttempt(orderId, productId, 2);

        assertThat(result).isSameAs(existing);
        verify(eventPublisher, never()).publishReserved(any());
    }

    @Test
    void reserveAttemptThrowsAndDoesNotPublishWhenStockIsInsufficient() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(reservationRepository.findByOrderIdAndProductId(orderId, productId)).thenReturn(Optional.empty());
        when(inventoryRepository.findByProductIdAndAvailableQuantityGreaterThanEqualOrderByAvailableQuantityDesc(productId, 2))
                .thenReturn(List.of());

        assertThatThrownBy(() -> operations().reserveAttempt(orderId, productId, 2))
                .isInstanceOf(InsufficientStockException.class);

        verify(eventPublisher, never()).publishReserved(any());
    }

    @Test
    void releaseAttemptPublishesReleasedOnceOnAnActualRelease() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Inventory inventory = inventoryWithStock(productId, 5);
        InventoryReservation reservation = new InventoryReservation(inventory, orderId, productId, 2);
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        when(reservationRepository.findByOrderIdAndProductId(orderId, productId)).thenReturn(Optional.of(reservation));
        when(reservationRepository.saveAndFlush(reservation)).thenReturn(reservation);

        InventoryReservation result = operations().releaseAttempt(orderId, productId);

        assertThat(result).isSameAs(reservation);
        verify(eventPublisher).publishReleased(new InventoryReleasedPayload(reservation.getId(), orderId, productId, 2));
    }

    @Test
    void releaseAttemptDoesNotRepublishForAnAlreadyReleasedReservation() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Inventory inventory = inventoryWithStock(productId, 5);
        InventoryReservation reservation = new InventoryReservation(inventory, orderId, productId, 2);
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        reservation.markReleased();
        when(reservationRepository.findByOrderIdAndProductId(orderId, productId)).thenReturn(Optional.of(reservation));

        InventoryReservation result = operations().releaseAttempt(orderId, productId);

        assertThat(result).isSameAs(reservation);
        verify(eventPublisher, never()).publishReleased(any());
    }
}
