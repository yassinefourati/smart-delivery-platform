package com.smartdelivery.inventory.service;

import com.smartdelivery.inventory.domain.InventoryReservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Retries a single reservation-lifecycle attempt (see InventoryReservationOperations)
 * when it loses an optimistic-locking race, instead of pushing that failure straight
 * to the caller. This absorbs the two-customers-want-the-last-item scenario the
 * platform is explicitly required to handle correctly (master brief section 8): if the
 * stock genuinely allows both requests, retrying lets both succeed; only a real
 * insufficient-stock condition (InsufficientStockException) is a real failure.
 *
 * This class is deliberately not {@code @Transactional} -- each retry must run as its
 * own fresh transaction so it re-reads the current committed state instead of retrying
 * inside a transaction that already saw a stale version. See
 * InventoryReservationOperations' class Javadoc for why the retry loop and the
 * transactional attempt cannot live in the same class.
 */
@Service
public class InventoryReservationService {

    private static final Logger log = LoggerFactory.getLogger(InventoryReservationService.class);
    private static final int MAX_ATTEMPTS = 5;

    private final InventoryReservationOperations operations;

    public InventoryReservationService(InventoryReservationOperations operations) {
        this.operations = operations;
    }

    public InventoryReservation reserve(UUID orderId, UUID productId, int quantity) {
        return withRetry("reserve", orderId, productId, () -> operations.reserveAttempt(orderId, productId, quantity));
    }

    public InventoryReservation release(UUID orderId, UUID productId) {
        return withRetry("release", orderId, productId, () -> operations.releaseAttempt(orderId, productId));
    }

    public InventoryReservation deduct(UUID orderId, UUID productId) {
        return withRetry("deduct", orderId, productId, () -> operations.deductAttempt(orderId, productId));
    }

    private InventoryReservation withRetry(String action, UUID orderId, UUID productId, Supplier<InventoryReservation> attempt) {
        ObjectOptimisticLockingFailureException lastFailure = null;
        for (int attemptNumber = 1; attemptNumber <= MAX_ATTEMPTS; attemptNumber++) {
            try {
                return attempt.get();
            } catch (ObjectOptimisticLockingFailureException e) {
                lastFailure = e;
                log.debug("Optimistic lock conflict on {} attempt {}/{} for order={} product={}, retrying",
                        action, attemptNumber, MAX_ATTEMPTS, orderId, productId);
            }
        }
        throw lastFailure;
    }
}
