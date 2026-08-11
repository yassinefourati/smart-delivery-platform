package com.smartdelivery.inventory.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryTest {

    private Inventory inventoryOf(int available) {
        Warehouse warehouse = new Warehouse("Main", "Somewhere");
        return new Inventory(UUID.randomUUID(), warehouse, available);
    }

    @Test
    void reserveMovesUnitsFromAvailableToReserved() {
        Inventory inventory = inventoryOf(10);

        inventory.reserve(3);

        assertThat(inventory.getAvailableQuantity()).isEqualTo(7);
        assertThat(inventory.getReservedQuantity()).isEqualTo(3);
    }

    @Test
    void reserveRejectsQuantityGreaterThanAvailable() {
        Inventory inventory = inventoryOf(2);

        assertThatThrownBy(() -> inventory.reserve(3)).isInstanceOf(IllegalStateException.class);

        assertThat(inventory.getAvailableQuantity()).isEqualTo(2);
        assertThat(inventory.getReservedQuantity()).isZero();
    }

    @Test
    void releaseMovesUnitsBackFromReservedToAvailable() {
        Inventory inventory = inventoryOf(10);
        inventory.reserve(4);

        inventory.release(4);

        assertThat(inventory.getAvailableQuantity()).isEqualTo(10);
        assertThat(inventory.getReservedQuantity()).isZero();
    }

    @Test
    void deductPermanentlyRemovesReservedUnitsWithoutRestockingAvailable() {
        Inventory inventory = inventoryOf(10);
        inventory.reserve(4);

        inventory.deduct(4);

        assertThat(inventory.getAvailableQuantity()).isEqualTo(6);
        assertThat(inventory.getReservedQuantity()).isZero();
    }
}
