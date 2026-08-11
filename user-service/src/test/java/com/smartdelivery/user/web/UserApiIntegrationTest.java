package com.smartdelivery.user.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdelivery.user.domain.Role;
import com.smartdelivery.user.domain.RoleName;
import com.smartdelivery.user.domain.User;
import com.smartdelivery.user.repository.RoleRepository;
import com.smartdelivery.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end verification through the real HTTP + Spring Security filter chain against
 * a real Postgres (Testcontainers) -- exercises scenarios 1, 2, 13, and 14 from the
 * master engineering brief's testing checklist (successful/invalid requests,
 * unauthorized, forbidden) for the User Service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class UserApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private UUID registerUser(String email, String password) throws Exception {
        var request = Map.of(
                "email", email,
                "password", password,
                "firstName", "Test",
                "lastName", "User",
                "phoneNumber", "555-0100");

        String responseBody = mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return UUID.fromString(objectMapper.readTree(responseBody).get("id").asText());
    }

    private String login(String email, String password) throws Exception {
        var request = Map.of("email", email, "password", password);
        String responseBody = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(responseBody).get("accessToken").asText();
    }

    private String registerLoginAndGetToken(String email, String password) throws Exception {
        registerUser(email, password);
        return login(email, password);
    }

    private String adminToken() {
        Role adminRole = roleRepository.findByName(RoleName.ADMIN).orElseThrow();
        String email = uniqueEmail();
        String rawPassword = "admin-password-123";
        User admin = new User(email, passwordEncoder.encode(rawPassword), "Admin", "User", null);
        admin.addRole(adminRole);
        userRepository.save(admin);
        try {
            return login(email, rawPassword);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void registerCreatesUserWithCustomerRole() throws Exception {
        String email = uniqueEmail();
        var request = Map.of(
                "email", email, "password", "password123",
                "firstName", "Jane", "lastName", "Doe", "phoneNumber", "555-0101");

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
    }

    @Test
    void registerRejectsDuplicateEmailWith409() throws Exception {
        String email = uniqueEmail();
        registerUser(email, "password123");

        var duplicate = Map.of(
                "email", email, "password", "password456",
                "firstName", "Other", "lastName", "Person", "phoneNumber", "555-0102");

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicate)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("EMAIL_ALREADY_EXISTS"));
    }

    @Test
    void registerRejectsInvalidPayloadWith400() throws Exception {
        var invalid = Map.of(
                "email", "not-an-email", "password", "short",
                "firstName", "", "lastName", "Doe");

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        String email = uniqueEmail();
        registerUser(email, "correct-password");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", "wrong-password"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void gettingUserWithoutTokenReturns401() throws Exception {
        UUID userId = registerUser(uniqueEmail(), "password123");

        mockMvc.perform(get("/api/v1/users/{id}", userId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void userCanReadAndUpdateTheirOwnProfile() throws Exception {
        String email = uniqueEmail();
        UUID userId = registerUser(email, "password123");
        String token = login(email, "password123");

        mockMvc.perform(get("/api/v1/users/{id}", userId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));

        var update = Map.of("firstName", "Updated", "lastName", "Name", "phoneNumber", "555-9999");
        mockMvc.perform(put("/api/v1/users/{id}", userId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Updated"));
    }

    @Test
    void userCannotReadAnotherUsersProfile() throws Exception {
        UUID otherUserId = registerUser(uniqueEmail(), "password123");

        String myEmail = uniqueEmail();
        String myToken = registerLoginAndGetToken(myEmail, "password123");

        mockMvc.perform(get("/api/v1/users/{id}", otherUserId)
                        .header("Authorization", "Bearer " + myToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void adminCanReadAnyUsersProfile() throws Exception {
        UUID otherUserId = registerUser(uniqueEmail(), "password123");
        String adminToken = adminToken();

        mockMvc.perform(get("/api/v1/users/{id}", otherUserId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void userCanManageTheirOwnAddresses() throws Exception {
        String email = uniqueEmail();
        UUID userId = registerUser(email, "password123");
        String token = login(email, "password123");

        var addressRequest = Map.of(
                "label", "Home", "street", "123 Main St", "city", "Springfield",
                "state", "IL", "postalCode", "62704", "country", "USA", "isDefault", true);

        String createResponse = mockMvc.perform(post("/api/v1/users/{id}/addresses", userId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addressRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.city").value("Springfield"))
                .andReturn().getResponse().getContentAsString();
        UUID addressId = UUID.fromString(objectMapper.readTree(createResponse).get("id").asText());

        mockMvc.perform(get("/api/v1/users/{id}/addresses", userId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(addressId.toString()));

        var updateRequest = Map.of(
                "label", "Work", "street", "456 Market St", "city", "Springfield",
                "state", "IL", "postalCode", "62704", "country", "USA", "isDefault", false);
        mockMvc.perform(put("/api/v1/users/{id}/addresses/{addressId}", userId, addressId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Work"));

        mockMvc.perform(delete("/api/v1/users/{id}/addresses/{addressId}", userId, addressId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/{id}/addresses", userId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void userCannotManageAnotherUsersAddresses() throws Exception {
        UUID otherUserId = registerUser(uniqueEmail(), "password123");
        String myToken = registerLoginAndGetToken(uniqueEmail(), "password123");

        var addressRequest = Map.of(
                "label", "Home", "street", "1 Nowhere Ave", "city", "Nowhere",
                "state", "NA", "postalCode", "00000", "country", "USA", "isDefault", false);

        mockMvc.perform(post("/api/v1/users/{id}/addresses", otherUserId)
                        .header("Authorization", "Bearer " + myToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addressRequest)))
                .andExpect(status().isForbidden());
    }
}
