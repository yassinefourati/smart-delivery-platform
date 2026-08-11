package com.smartdelivery.order.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
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

import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end verification through the real HTTP + Spring Security filter chain against
 * a real Postgres (Testcontainers). product-service itself is not started -- its
 * synchronous REST calls (ProductServiceClient) are intercepted with
 * {@link MockRestServiceServer}, which is the standard Spring approach for testing a
 * {@code RestClient} consumer without standing up the real dependency. This keeps
 * order-service independently testable, per docs/service-boundaries.md.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@Import(OrderApiIntegrationTest.RestClientTestConfig.class)
class OrderApiIntegrationTest {

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

    @TestConfiguration
    static class RestClientTestConfig {
        @Bean
        RestClient.Builder testRestClientBuilder() {
            return RestClient.builder();
        }

        @Bean
        MockRestServiceServer mockRestServiceServer(RestClient.Builder builder) {
            return MockRestServiceServer.bindTo(builder).build();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockRestServiceServer mockRestServiceServer;

    @BeforeEach
    void resetProductServiceStub() {
        mockRestServiceServer.reset();
    }

    private String tokenFor(UUID userId, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("roles", List.of(role))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(SIGNING_KEY)
                .compact();
    }

    private void stubProduct(UUID productId, String name, String price, boolean active) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "id", productId.toString(), "name", name, "price", price, "active", active));
        mockRestServiceServer.expect(requestTo("http://localhost:8082/api/v1/products/" + productId))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void stubProductNotFound(UUID productId) {
        mockRestServiceServer.expect(requestTo("http://localhost:8082/api/v1/products/" + productId))
                .andRespond(withStatus(NOT_FOUND));
    }

    private Map<String, Object> orderRequest(UUID addressId, UUID productId, int quantity) {
        return Map.of("shippingAddressId", addressId.toString(),
                "items", List.of(Map.of("productId", productId.toString(), "quantity", quantity)));
    }

    @Test
    void createsAnOrderPricedFromProductService() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        stubProduct(productId, "Widget", "9.99", true);

        mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + tokenFor(userId, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderRequest(UUID.randomUUID(), productId, 3))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.totalAmount").value(29.97))
                .andExpect(jsonPath("$.items[0].productName").value("Widget"));

        mockRestServiceServer.verify();
    }

    @Test
    void creatingAnOrderForAnUnknownProductReturns400() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        stubProductNotFound(productId);

        mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + tokenFor(userId, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderRequest(UUID.randomUUID(), productId, 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PRODUCT"));
    }

    @Test
    void creatingAnOrderForAnInactiveProductReturns409() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        stubProduct(productId, "Discontinued Widget", "9.99", false);

        mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + tokenFor(userId, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderRequest(UUID.randomUUID(), productId, 1))))
                .andExpect(status().isConflict());
    }

    @Test
    void creatingAnOrderWithoutATokenReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderRequest(UUID.randomUUID(), UUID.randomUUID(), 1))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void repeatedRequestWithTheSameIdempotencyKeyDoesNotCreateADuplicateOrCallProductServiceTwice() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        // Exactly one expectation: if the second POST reached product-service again,
        // MockRestServiceServer would fail this test with "no further requests expected".
        stubProduct(productId, "Widget", "9.99", true);
        String token = tokenFor(userId, "CUSTOMER");
        String body = objectMapper.writeValueAsString(orderRequest(UUID.randomUUID(), productId, 1));

        String first = mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "replay-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "replay-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String firstId = objectMapper.readTree(first).get("id").asText();
        String secondId = objectMapper.readTree(second).get("id").asText();
        org.assertj.core.api.Assertions.assertThat(secondId).isEqualTo(firstId);
        mockRestServiceServer.verify();
    }

    @Test
    void reusingAnIdempotencyKeyWithADifferentBodyReturns409() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        stubProduct(productId, "Widget", "9.99", true);
        String token = tokenFor(userId, "CUSTOMER");

        mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "reused-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderRequest(UUID.randomUUID(), productId, 1))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "reused-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderRequest(UUID.randomUUID(), productId, 5))))
                .andExpect(status().isConflict());
    }

    @Test
    void ownerCanReadTheirOwnOrderButNotOthers() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        stubProduct(productId, "Widget", "9.99", true);

        String createResponse = mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + tokenFor(ownerId, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderRequest(UUID.randomUUID(), productId, 1))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = objectMapper.readTree(createResponse).get("id").asText();

        mockMvc.perform(get("/api/v1/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + tokenFor(ownerId, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId));

        mockMvc.perform(get("/api/v1/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + tokenFor(UUID.randomUUID(), "CUSTOMER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + tokenFor(UUID.randomUUID(), "ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void statusEndpointReturnsJustIdAndStatus() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        stubProduct(productId, "Widget", "9.99", true);

        String createResponse = mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + tokenFor(ownerId, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderRequest(UUID.randomUUID(), productId, 1))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = objectMapper.readTree(createResponse).get("id").asText();

        mockMvc.perform(get("/api/v1/orders/{id}/status", orderId)
                        .header("Authorization", "Bearer " + tokenFor(ownerId, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void listByUserIsRestrictedToSelfOrAdmin() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        stubProduct(productId, "Widget", "9.99", true);

        mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + tokenFor(ownerId, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderRequest(UUID.randomUUID(), productId, 1))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/orders/user/{userId}", ownerId)
                        .header("Authorization", "Bearer " + tokenFor(ownerId, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/v1/orders/user/{userId}", ownerId)
                        .header("Authorization", "Bearer " + tokenFor(UUID.randomUUID(), "CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void cancellingAnOrderTwiceTheSecondTimeReturns409() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        stubProduct(productId, "Widget", "9.99", true);
        String token = tokenFor(ownerId, "CUSTOMER");

        String createResponse = mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderRequest(UUID.randomUUID(), productId, 1))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = objectMapper.readTree(createResponse).get("id").asText();

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }
}
