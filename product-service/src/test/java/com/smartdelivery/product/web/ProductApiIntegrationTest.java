package com.smartdelivery.product.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.product.domain.Category;
import com.smartdelivery.product.domain.Product;
import com.smartdelivery.product.repository.CategoryRepository;
import com.smartdelivery.product.repository.ProductRepository;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end verification through the real HTTP + Spring Security filter chain,
 * a real Postgres, and a real Redis (Testcontainers) -- covers CRUD, search/
 * pagination/filtering, ADMIN-only write enforcement, and cache-then-invalidate
 * behavior for the product cache (ADR 005).
 *
 * product-service never issues JWTs itself (only user-service does), so this test
 * mints tokens directly with the same shared secret to exercise authorization without
 * depending on user-service being up -- consistent with each service being
 * independently testable (docs/service-boundaries.md).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class ProductApiIntegrationTest {

    private static final String JWT_SECRET = "integration-test-secret-key-must-be-at-least-32-bytes";
    private static final SecretKey SIGNING_KEY = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("jwt.secret", () -> JWT_SECRET);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

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

    private String adminToken() {
        return tokenWithRole("ADMIN");
    }

    private String customerToken() {
        return tokenWithRole("CUSTOMER");
    }

    private Category persistCategory(String name) {
        return categoryRepository.save(new Category(name, "desc"));
    }

    private Product persistProduct(Category category, String sku, String name, BigDecimal price) {
        return productRepository.save(new Product(sku, name, "desc", price, null, true, category));
    }

    @Test
    void anyoneCanBrowseTheCatalogWithoutAToken() throws Exception {
        Category category = persistCategory("Browsable-" + UUID.randomUUID());
        persistProduct(category, "SKU-" + UUID.randomUUID(), "Public Widget", BigDecimal.TEN);

        mockMvc.perform(get("/api/v1/products").param("categoryId", category.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Public Widget"));
    }

    @Test
    void creatingAProductWithoutATokenReturns401() throws Exception {
        Category category = persistCategory("NoToken-" + UUID.randomUUID());
        var request = Map.of(
                "sku", "SKU-" + UUID.randomUUID(), "name", "Widget", "description", "desc",
                "price", 9.99, "active", true, "categoryId", category.getId().toString());

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void creatingAProductAsCustomerReturns403() throws Exception {
        Category category = persistCategory("CustomerDenied-" + UUID.randomUUID());
        var request = Map.of(
                "sku", "SKU-" + UUID.randomUUID(), "name", "Widget", "description", "desc",
                "price", 9.99, "active", true, "categoryId", category.getId().toString());

        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + customerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanCreateAProduct() throws Exception {
        Category category = persistCategory("AdminCreate-" + UUID.randomUUID());
        var request = Map.of(
                "sku", "SKU-" + UUID.randomUUID(), "name", "Admin Widget", "description", "desc",
                "price", 12.50, "active", true, "categoryId", category.getId().toString());

        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Admin Widget"));
    }

    @Test
    void creatingAProductWithADuplicateSkuReturns409() throws Exception {
        Category category = persistCategory("Dup-" + UUID.randomUUID());
        String sku = "SKU-" + UUID.randomUUID();
        persistProduct(category, sku, "Existing", BigDecimal.ONE);

        var request = Map.of(
                "sku", sku, "name", "Another", "description", "desc",
                "price", 5.0, "active", true, "categoryId", category.getId().toString());

        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void searchFiltersByCategoryPriceRangeAndText() throws Exception {
        Category category = persistCategory("SearchCat-" + UUID.randomUUID());
        Category otherCategory = persistCategory("OtherCat-" + UUID.randomUUID());
        persistProduct(category, "SKU-" + UUID.randomUUID(), "Blue Mug", new BigDecimal("9.00"));
        persistProduct(category, "SKU-" + UUID.randomUUID(), "Red Mug", new BigDecimal("25.00"));
        persistProduct(otherCategory, "SKU-" + UUID.randomUUID(), "Blue Mug", new BigDecimal("9.00"));

        mockMvc.perform(get("/api/v1/products")
                        .param("categoryId", category.getId().toString())
                        .param("minPrice", "5")
                        .param("maxPrice", "15")
                        .param("search", "blue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Blue Mug"));
    }

    @Test
    void gettingAProductCachesItUntilItIsUpdated() throws Exception {
        Category category = persistCategory("Cache-" + UUID.randomUUID());
        Product product = persistProduct(category, "SKU-" + UUID.randomUUID(), "Cached Widget", new BigDecimal("10.00"));

        mockMvc.perform(get("/api/v1/products/{id}", product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(10.00));

        // Mutate the row directly, bypassing ProductService (and therefore the cache
        // eviction it's responsible for) to prove the second read below is served from
        // Redis rather than hitting Postgres again.
        Product row = productRepository.findById(product.getId()).orElseThrow();
        row.setPrice(new BigDecimal("999.00"));
        productRepository.saveAndFlush(row);

        mockMvc.perform(get("/api/v1/products/{id}", product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(10.00));

        var updateRequest = Map.of(
                "sku", product.getSku(), "name", "Cached Widget", "description", "desc",
                "price", 999.00, "active", true, "categoryId", category.getId().toString());
        mockMvc.perform(put("/api/v1/products/{id}", product.getId())
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/products/{id}", product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(999.00));
    }

    @Test
    void deletingAProductRemovesItAndItsCacheEntry() throws Exception {
        Category category = persistCategory("Delete-" + UUID.randomUUID());
        Product product = persistProduct(category, "SKU-" + UUID.randomUUID(), "Doomed Widget", BigDecimal.ONE);

        mockMvc.perform(get("/api/v1/products/{id}", product.getId())).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/products/{id}", product.getId())
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/products/{id}", product.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingACategoryStillReferencedByProductsReturns409() throws Exception {
        Category category = persistCategory("InUse-" + UUID.randomUUID());
        persistProduct(category, "SKU-" + UUID.randomUUID(), "Widget", BigDecimal.ONE);

        mockMvc.perform(delete("/api/v1/categories/{id}", category.getId())
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isConflict());
    }
}
