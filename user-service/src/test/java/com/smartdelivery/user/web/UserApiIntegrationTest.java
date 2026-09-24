package com.smartdelivery.user.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    @Autowired
    private PlatformTransactionManager transactionManager;

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

    /**
     * The lookup and the save share one transaction on purpose. Each repository call
     * otherwise runs in its own, which leaves the ADMIN {@code Role} detached by the time
     * it is attached to a brand-new {@code User} -- and {@code User.roles} cascades
     * {@code PERSIST}, so saving the user cascades a persist onto a detached entity and
     * Hibernate rightly refuses. One persistence context keeps the role managed, and the
     * cascade becomes the no-op it should be.
     */
    private String adminToken() {
        String email = uniqueEmail();
        String rawPassword = "admin-password-123";
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Role adminRole = roleRepository.findByName(RoleName.ADMIN).orElseThrow();
            User admin = new User(email, passwordEncoder.encode(rawPassword), "Admin", "User", null);
            admin.addRole(adminRole);
            userRepository.save(admin);
        });
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

    // --- Role management: the only way to a role other than CUSTOMER -------------------

    @Test
    void adminFindsAUserByEmail() throws Exception {
        String email = uniqueEmail();
        UUID userId = registerUser(email, "password123");

        mockMvc.perform(get("/api/v1/users/lookup").param("email", email)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()));
    }

    @Test
    void lookingUpAnUnknownEmailReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/users/lookup").param("email", uniqueEmail())
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void aCustomerCannotLookUpUsersOrGrantRoles() throws Exception {
        String myEmail = uniqueEmail();
        UUID myId = registerUser(myEmail, "password123");
        String myToken = login(myEmail, "password123");

        mockMvc.perform(get("/api/v1/users/lookup").param("email", myEmail)
                        .header("Authorization", "Bearer " + myToken))
                .andExpect(status().isForbidden());
        // Least of all to themselves.
        mockMvc.perform(put("/api/v1/users/{id}/roles/{role}", myId, "ADMIN")
                        .header("Authorization", "Bearer " + myToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void aGrantedRoleIsInTheUsersNextToken() throws Exception {
        String email = uniqueEmail();
        UUID userId = registerUser(email, "password123");
        String adminToken = adminToken();

        mockMvc.perform(put("/api/v1/users/{id}/roles/{role}", userId, "DELIVERY_AGENT")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(2));
        // Granting it again is a no-op, not an error.
        mockMvc.perform(put("/api/v1/users/{id}/roles/{role}", userId, "DELIVERY_AGENT")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(2));

        org.assertj.core.api.Assertions.assertThat(
                        SignedJWT.parse(login(email, "password123")).getJWTClaimsSet().getStringListClaim("roles"))
                .containsExactlyInAnyOrder("CUSTOMER", "DELIVERY_AGENT");
    }

    @Test
    void aRevokedRoleIsGoneFromTheUser() throws Exception {
        UUID userId = registerUser(uniqueEmail(), "password123");
        String adminToken = adminToken();
        mockMvc.perform(put("/api/v1/users/{id}/roles/{role}", userId, "WAREHOUSE_MANAGER")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/users/{id}/roles/{role}", userId, "WAREHOUSE_MANAGER")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
    }

    @Test
    void anUnknownRoleIsA400() throws Exception {
        UUID userId = registerUser(uniqueEmail(), "password123");

        mockMvc.perform(put("/api/v1/users/{id}/roles/{role}", userId, "SUPERUSER")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isBadRequest());
    }

    /** Otherwise the last admin could remove administration from the platform in one click. */
    @Test
    void anAdminCannotRevokeTheirOwnAdminRole() throws Exception {
        String adminToken = adminToken();
        UUID adminId = UUID.fromString(SignedJWT.parse(adminToken).getJWTClaimsSet().getSubject());

        mockMvc.perform(delete("/api/v1/users/{id}/roles/{role}", adminId, "ADMIN")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CANNOT_REVOKE_OWN_ADMIN"));
    }

    // --- Phase 16: asymmetric signing, JWKS, and client credentials (ADR 007) ---------

    /**
     * A resource server has to fetch this before it can authenticate anything, including
     * before it has a token to authenticate with -- so requiring one would be circular.
     */
    @Test
    void theJwksEndpointIsReachableWithoutAToken() throws Exception {
        mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").isArray())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].kid").exists())
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"));
    }

    /**
     * The endpoint is public, so this is the assertion that matters most about it: `n`
     * and `e` are the public modulus and exponent; `d`, `p`, and `q` are the private
     * exponent and its prime factors, and publishing any of them would hand out the
     * signing key.
     */
    @Test
    void theJwksEndpointPublishesNoPrivateKeyMaterial() throws Exception {
        String body = mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode key = objectMapper.readTree(body).get("keys").get(0);
        org.assertj.core.api.Assertions.assertThat(key.has("n")).isTrue();
        org.assertj.core.api.Assertions.assertThat(key.has("e")).isTrue();
        org.assertj.core.api.Assertions.assertThat(key.has("d")).isFalse();
        org.assertj.core.api.Assertions.assertThat(key.has("p")).isFalse();
        org.assertj.core.api.Assertions.assertThat(key.has("q")).isFalse();
    }

    /** Without a matching kid a resource server holding several keys could not pick one. */
    @Test
    void anIssuedTokenNamesAKeyThatTheJwksActuallyPublishes() throws Exception {
        String token = registerLoginAndGetToken(uniqueEmail(), "password123");
        String jwks = mockMvc.perform(get("/.well-known/jwks.json"))
                .andReturn().getResponse().getContentAsString();

        String tokenKid = SignedJWT.parse(token).getHeader().getKeyID();
        org.assertj.core.api.Assertions.assertThat(SignedJWT.parse(token).getHeader().getAlgorithm().getName())
                .isEqualTo("RS256");
        org.assertj.core.api.Assertions.assertThat(objectMapper.readTree(jwks).get("keys").findValuesAsText("kid"))
                .contains(tokenKid);
    }

    @Test
    void aServiceExchangesItsClientCredentialsForAServiceToken() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/service-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "clientId", "order-service",
                                "clientSecret", "local-dev-only-order-service-secret"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").isNumber())
                .andReturn().getResponse().getContentAsString();

        String serviceToken = objectMapper.readTree(body).get("accessToken").asText();
        JWTClaimsSet claims = SignedJWT.parse(serviceToken).getJWTClaimsSet();
        org.assertj.core.api.Assertions.assertThat(claims.getSubject()).isEqualTo("order-service");
        org.assertj.core.api.Assertions.assertThat(claims.getStringListClaim("roles")).containsExactly("SERVICE");

        // Authenticated, but with no business here: a SERVICE token gets past the door
        // and no further, which is the point of giving it its own role.
        mockMvc.perform(get("/api/v1/users/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + serviceToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void aWrongClientSecretGets401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/service-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "clientId", "order-service", "clientSecret", "guessed"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownClientIdGets401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/service-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "clientId", "inventory-service", "clientSecret", "anything"))))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The vulnerability Phase 16 closed, asserted from the outside: this token is signed
     * with the secret every service used to share, and it claims ADMIN.
     */
    @Test
    void aTokenSignedWithTheOldSharedHmacSecretIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/users/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + legacyHmacToken()))
                .andExpect(status().isUnauthorized());
    }

    private String legacyHmacToken() throws Exception {
        String legacySecret = "local-dev-only-secret-key-do-not-use-in-production-min-32-bytes";
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).build(),
                new JWTClaimsSet.Builder()
                        .subject(UUID.randomUUID().toString())
                        .issuer("https://user-service.smart-delivery.local")
                        .audience("smart-delivery-platform")
                        .claim("roles", List.of("ADMIN"))
                        .issueTime(Date.from(Instant.now()))
                        .expirationTime(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                        .build());
        jwt.sign(new MACSigner(legacySecret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
