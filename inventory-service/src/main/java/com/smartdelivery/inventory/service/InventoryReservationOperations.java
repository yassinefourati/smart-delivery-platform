package com.smartdelivery.inventory.service;

import com.smartdelivery.inventory.domain.Inventory;
import com.smartdelivery.inventory.domain.InventoryReservation;
import com.smartdelivery.inventory.domain.ReservationStatus;
import com.smartdelivery.inventory.event.InventoryEventPublisher;
import com.smartdelivery.inventory.event.InventoryReleasedPayload;
import com.smartdelivery.inventory.event.InventoryReservedPayload;
import com.smartdelivery.inventory.exception.InsufficientStockException;
import com.smartdelivery.inventory.exception.InvalidReservationStateException;
import com.smartdelivery.inventory.exception.ReservationNotFoundException;
import com.smartdelivery.inventory.repository.InventoryRepository;
import com.smartdelivery.inventory.repository.InventoryReservationRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * A single, self-contained attempt at each reservation-lifecycle operation. Each
 * method here is one transaction from start to finish, and uses
 * {@code saveAndFlush} deliberately so that an optimistic-lock conflict
 * ({@link org.springframework.orm.ObjectOptimisticLockingFailureException}) surfaces
 * to the caller of this method -- not silently at commit time after the method has
 * already returned.
 *
 * This class is intentionally NOT where the retry loop lives: {@link InventoryReservationService}
 * calls these methods from a separate bean so each retry is a genuinely fresh
 * Spring-proxied transaction. A retry loop living inside this same class would call
 * itself directly (bypassing the transactional proxy via self-invocation) and would
 * not get a fresh transaction per attempt -- a classic Spring AOP pitfall.
 */
@Service
public class InventoryReservationOperations {

    private final InventoryRepository inventoryRepository;
    private final InventoryReservationRepository reservationRepository;
    private final InventoryEventPublisher eventPublisher;

    public InventoryReservationOperations(
            InventoryRepository inventoryRepository, InventoryReservationRepository reservationRepository,
            InventoryEventPublisher eventPublisher) {
        this.inventoryRepository = inventoryRepository;
        this.reservationRepository = reservationRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * The outbox write for {@code InventoryReserved} happens here, inside the same
     * transaction as the reservation itself (ADR 004) -- not in
     * {@link InventoryReservationService}, which calls this method from outside any
     * transaction of its own (see that class's Javadoc). Only the actual-creation path
     * publishes; the idempotent-existing-reservation short-circuit above does not
     * re-announce an outcome that was already announced the first time.
     */
    @Transactional
    public InventoryReservation reserveAttempt(UUID orderId, UUID productId, int quantity) {
        var existing = reservationRepository.findByOrderIdAndProductId(orderId, productId);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Deliberate simplification: a reservation must be satisfiable from a single
        // warehouse. We do not split one order line across multiple warehouses, even
        // if their combined stock would cover it -- that would mean one logical
        // reservation spanning several Inventory rows (and several optimistic locks to
        // coordinate), which is materially more complex for a scenario the business
        // requirements don't currently call for. Documented in docs/service-boundaries.md.
        List<Inventory> candidates = inventoryRepository
                .findByProductIdAndAvailableQuantityGreaterThanEqualOrderByAvailableQuantityDesc(productId, quantity);
        if (candidates.isEmpty()) {
            throw new InsufficientStockException(productId, quantity);
        }
        Inventory inventory = candidates.get(0);
        inventory.reserve(quantity);
        inventoryRepository.saveAndFlush(inventory);

        InventoryReservation reservation = new InventoryReservation(inventory, orderId, productId, quantity);
        InventoryReservation saved;
        try {
            saved = reservationRepository.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException e) {
            // Another concurrent request for the exact same (orderId, productId) won the
            // unique-constraint race after our existence check above; treat this as the
            // same idempotent outcome rather than an error.
            return reservationRepository.findByOrderIdAndProductId(orderId, productId).orElseThrow(() -> e);
        }
        eventPublisher.publishReserved(new InventoryReservedPayload(saved.getId(), orderId, productId, quantity));
        return saved;
    }

    /** Same reasoning as {@link #reserveAttempt}: the outbox write is atomic with the release. */
    @Transactional
    public InventoryReservation releaseAttempt(UUID orderId, UUID productId) {
        InventoryReservation reservation = findReservation(orderId, productId);

        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            return reservation;
        }
        if (reservation.getStatus() == ReservationStatus.DEDUCTED) {
            throw new InvalidReservationStateException("release", ReservationStatus.DEDUCTED);
        }

        Inventory inventory = reservation.getInventory();
        inventory.release(reservation.getQuantity());
        inventoryRepository.saveAndFlush(inventory);

        reservation.markReleased();
        InventoryReservation saved = reservationRepository.saveAndFlush(reservation);
        eventPublisher.publishReleased(new InventoryReleasedPayload(saved.getId(), orderId, productId, saved.getQuantity()));
        return saved;
    }

    @Transactional
    public InventoryReservation deductAttempt(UUID orderId, UUID productId) {
        InventoryReservation reservation = findReservation(orderId, productId);

        if (reservation.getStatus() == ReservationStatus.DEDUCTED) {
            return reservation;
        }
        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            throw new InvalidReservationStateException("deduct", ReservationStatus.RELEASED);
        }

        Inventory inventory = reservation.getInventory();
        inventory.deduct(reservation.getQuantity());
        inventoryRepository.saveAndFlush(inventory);

        reservation.markDeducted();
        return reservationRepository.saveAndFlush(reservation);
    }

    private InventoryReservation findReservation(UUID orderId, UUID productId) {
        return reservationRepository.findByOrderIdAndProductId(orderId, productId)
                .orElseThrow(() -> new ReservationNotFoundException(orderId, productId));
    }
}
