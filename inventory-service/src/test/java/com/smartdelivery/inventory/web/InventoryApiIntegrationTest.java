package com.smartdelivery.inventory.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.inventory.domain.Inventory;
import com.smartdelivery.inventory.domain.Warehouse;
import com.smartdelivery.inventory.repository.InventoryRepository;
import com.smartdelivery.inventory.repository.WarehouseRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end verification through the real HTTP + Spring Security filter chain
 * against a real Postgres (Testcontainers). The concurrency tests near the bottom are
 * the scenario the master engineering brief calls out explicitly (section 8): two
 * customers competing for the last unit of a product must never result in overselling.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class InventoryApiIntegrationTest {

    private static final String JWT_SECRET = "integration-test-secret-key-must-be-at-least-32-bytes";
    private static final SecretKey SIGNING_KEY = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("jwt.secret", () -> JWT_SECRET);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    private String tokenWithRole(String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("roles", List.of(role))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(SIGNING_KEY)
                .compact();
    }

    private String warehouseManagerToken() {
        return tokenWithRole("WAREHOUSE_MANAGER");
    }

    private String customerToken() {
        return tokenWithRole("CUSTOMER");
    }

    private Warehouse persistWarehouse(String name) {
        return warehouseRepository.save(new Warehouse(name, "123 Dock Rd"));
    }

    private Inventory persistInventory(Warehouse warehouse, UUID productId, int availableQuantity) {
        return inventoryRepository.save(new Inventory(productId, warehouse, availableQuantity));
    }

    @Test
    void warehouseManagerCanCreateWarehouseAndStockInventory() throws Exception {
        var warehouseRequest = Map.of("name", "WH-" + UUID.randomUUID(), "location", "42 Industrial Ave");
        String createWarehouseResponse = mockMvc.perform(post("/api/v1/warehouses")
                        .header("Authorization", "Bearer " + warehouseManagerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(warehouseRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID warehouseId = UUID.fromString(objectMapper.readTree(createWarehouseResponse).get("id").asText());

        UUID productId = UUID.randomUUID();
        var inventoryRequest = Map.of("productId", productId.toString(), "warehouseId", warehouseId.toString(), "availableQuantity", 50);
        mockMvc.perform(post("/api/v1/inventory")
                        .header("Authorization", "Bearer " + warehouseManagerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(inventoryRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.availableQuantity").value(50));
    }

    @Test
    void anyoneCanReadTheInventorySummaryWithoutAToken() throws Exception {
        Warehouse warehouse = persistWarehouse("WH-" + UUID.randomUUID());
        UUID productId = UUID.randomUUID();
        persistInventory(warehouse, productId, 30);

        mockMvc.perform(get("/api/v1/inventory/{productId}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAvailable").value(30));
    }

    @Test
    void creatingInventoryAsCustomerReturns403() throws Exception {
        var request = Map.of("productId", UUID.randomUUID().toString(), "warehouseId", UUID.randomUUID().toString(), "availableQuantity", 10);

        mockMvc.perform(post("/api/v1/inventory")
                        .header("Authorization", "Bearer " + customerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void reservingWithoutATokenReturns401() throws Exception {
        var request = Map.of("orderId", UUID.randomUUID().toString(), "productId", UUID.randomUUID().toString(), "quantity", 1);

        mockMvc.perform(post("/api/v1/inventory/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reservingMoreThanAvailableReturns409() throws Exception {
        Warehouse warehouse = persistWarehouse("WH-" + UUID.randomUUID());
        UUID productId = UUID.randomUUID();
        persistInventory(warehouse, productId, 2);

        var request = Map.of("orderId", UUID.randomUUID().toString(), "productId", productId.toString(), "quantity", 5);

        mockMvc.perform(post("/api/v1/inventory/reserve")
                        .header("Authorization", "Bearer " + warehouseManagerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void reservingThenReleasingRestoresAvailableQuantity() throws Exception {
        Warehouse warehouse = persistWarehouse("WH-" + UUID.randomUUID());
        UUID productId = UUID.randomUUID();
        persistInventory(warehouse, productId, 10);
        UUID orderId = UUID.randomUUID();

        reserve(orderId, productId, 4, warehouseManagerToken()).andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/inventory/{productId}", productId))
                .andExpect(jsonPath("$.totalAvailable").value(6))
                .andExpect(jsonPath("$.totalReserved").value(4));

        var releaseRequest = Map.of("orderId", orderId.toString(), "productId", productId.toString());
        mockMvc.perform(post("/api/v1/inventory/release")
                        .header("Authorization", "Bearer " + warehouseManagerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(releaseRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));

        mockMvc.perform(get("/api/v1/inventory/{productId}", productId))
                .andExpect(jsonPath("$.totalAvailable").value(10))
                .andExpect(jsonPath("$.totalReserved").value(0));

        // Idempotent: releasing an already-released reservation is a no-op, not an error.
        mockMvc.perform(post("/api/v1/inventory/release")
                        .header("Authorization", "Bearer " + warehouseManagerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(releaseRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
    }

    @Test
    void reservingThenDeductingPermanentlyRemovesReservedQuantity() throws Exception {
        Warehouse warehouse = persistWarehouse("WH-" + UUID.randomUUID());
        UUID productId = UUID.randomUUID();
        persistInventory(warehouse, productId, 10);
        UUID orderId = UUID.randomUUID();

        reserve(orderId, productId, 4, warehouseManagerToken()).andExpect(status().isCreated());

        var deductRequest = Map.of("orderId", orderId.toString(), "productId", productId.toString());
        mockMvc.perform(post("/api/v1/inventory/deduct")
                        .header("Authorization", "Bearer " + warehouseManagerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deductRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEDUCTED"));

        mockMvc.perform(get("/api/v1/inventory/{productId}", productId))
                .andExpect(jsonPath("$.totalAvailable").value(6))
                .andExpect(jsonPath("$.totalReserved").value(0));

        // Idempotent: deducting an already-deducted reservation is a no-op.
        mockMvc.perform(post("/api/v1/inventory/deduct")
                        .header("Authorization", "Bearer " + warehouseManagerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deductRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEDUCTED"));

        // A deducted reservation can no longer be released -- that would require a refund/
        // restock flow, not a plain release.
        var releaseRequest = Map.of("orderId", orderId.toString(), "productId", productId.toString());
        mockMvc.perform(post("/api/v1/inventory/release")
                        .header("Authorization", "Bearer " + warehouseManagerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(releaseRequest)))
                .andExpect(status().isConflict());
    }

    @Test
    void repeatedReserveRequestForTheSameOrderAndProductIsIdempotent() throws Exception {
        Warehouse warehouse = persistWarehouse("WH-" + UUID.randomUUID());
        UUID productId = UUID.randomUUID();
        persistInventory(warehouse, productId, 10);
        UUID orderId = UUID.randomUUID();

        String first = reserve(orderId, productId, 3, warehouseManagerToken())
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = reserve(orderId, productId, 3, warehouseManagerToken())
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String firstId = objectMapper.readTree(first).get("reservationId").asText();
        String secondId = objectMapper.readTree(second).get("reservationId").asText();
        assertThat(secondId).isEqualTo(firstId);

        mockMvc.perform(get("/api/v1/inventory/{productId}", productId))
                .andExpect(jsonPath("$.totalAvailable").value(7)); // decremented once, not twice
    }

    /**
     * The scenario called out explicitly in the master engineering brief (section 8):
     * exactly one unit is available; two different orders race to reserve it
     * concurrently. Exactly one must succeed, the other must see a genuine
     * insufficient-stock conflict, and the final state must never go negative or
     * double-reserve.
     */
    @Test
    void concurrentReservationsForTheLastUnitNeverOversell() throws Exception {
        Warehouse warehouse = persistWarehouse("WH-" + UUID.randomUUID());
        UUID productId = UUID.randomUUID();
        persistInventory(warehouse, productId, 1);

        UUID orderA = UUID.randomUUID();
        UUID orderB = UUID.randomUUID();
        String token = warehouseManagerToken();

        List<Integer> statuses = runConcurrently(
                () -> reserve(orderA, productId, 1, token).andReturn().getResponse().getStatus(),
                () -> reserve(orderB, productId, 1, token).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);

        mockMvc.perform(get("/api/v1/inventory/{productId}", productId))
                .andExpect(jsonPath("$.totalAvailable").value(0))
                .andExpect(jsonPath("$.totalReserved").value(1));
    }

    /**
     * The companion case: when stock genuinely allows both concurrent requests, lock
     * contention alone must not spuriously fail one of them -- InventoryReservationService's
     * retry-on-conflict is what makes this true rather than a coin-flip. See that
     * class's Javadoc.
     */
    @Test
    void concurrentReservationsThatBothFitAvailableStockBothSucceed() throws Exception {
        Warehouse warehouse = persistWarehouse("WH-" + UUID.randomUUID());
        UUID productId = UUID.randomUUID();
        persistInventory(warehouse, productId, 2);

        UUID orderA = UUID.randomUUID();
        UUID orderB = UUID.randomUUID();
        String token = warehouseManagerToken();

        List<Integer> statuses = runConcurrently(
                () -> reserve(orderA, productId, 1, token).andReturn().getResponse().getStatus(),
                () -> reserve(orderB, productId, 1, token).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactly(201, 201);

        mockMvc.perform(get("/api/v1/inventory/{productId}", productId))
                .andExpect(jsonPath("$.totalAvailable").value(0))
                .andExpect(jsonPath("$.totalReserved").value(2));
    }

    private org.springframework.test.web.servlet.ResultActions reserve(UUID orderId, UUID productId, int quantity, String token) throws Exception {
        var request = Map.of("orderId", orderId.toString(), "productId", productId.toString(), "quantity", quantity);
        return mockMvc.perform(post("/api/v1/inventory/reserve")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    @SafeVarargs
    private List<Integer> runConcurrently(Callable<Integer>... tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.length);
        CyclicBarrier barrier = new CyclicBarrier(tasks.length);
        try {
            List<Future<Integer>> futures = java.util.Arrays.stream(tasks)
                    .map(task -> executor.submit((Callable<Integer>) () -> {
                        barrier.await();
                        return task.call();
                    }))
                    .toList();

            List<Integer> results = new java.util.ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdown();
        }
    }
}
