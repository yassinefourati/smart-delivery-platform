package com.smartdelivery.order.security;

import com.smartdelivery.platform.autoconfigure.PlatformSecurityAutoConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The token-acceptance contract every service in the platform now shares (ADR 007):
 * which tokens get in, which get a 401, and what a rejection looks like on the wire.
 *
 * This is the test that would have caught the vulnerability Phase 16 closed. Under the
 * previous scheme, {@link #aTokenSignedWithTheOldSharedHmacSecretIsRejected} would have
 * <em>passed authentication</em> -- the secret it signs with is the one every service
 * held, so any of them could have minted the ADMIN token it presents.
 *
 * Deliberately not a full {@code @SpringBootTest} of the application, which would need
 * Postgres and Kafka: this is a narrow slice of exactly the beans the contract depends
 * on -- the real {@link SecurityConfig}, and the real converter, entry point, and
 * access-denied handler that {@link PlatformSecurityAutoConfiguration} now supplies
 * (Phase 19, ADR 009), plus Spring Security's own resource-server autoconfiguration --
 * pointed at a stub JWKS endpoint. Same approach, and the same motivation, as
 * {@code ResilienceIntegrationTest}: it runs without containers, so it runs everywhere.
 */
@SpringBootTest(classes = JwtResourceServerIntegrationTest.TestApp.class)
@AutoConfigureMockMvc
@ImportAutoConfiguration({
        JacksonAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
        WebMvcAutoConfiguration.class,
        DispatcherServletAutoConfiguration.class,
        SecurityAutoConfiguration.class,
        OAuth2ResourceServerAutoConfiguration.class,
        // The shared converter, entry point, and access-denied handler. Imported as the
        // auto-configuration rather than as three bean classes, so this test exercises
        // the wiring a real service actually gets rather than a hand-assembled lookalike.
        PlatformSecurityAutoConfiguration.class
})
class JwtResourceServerIntegrationTest {

    private static final TestJwtIssuer JWT_ISSUER = new TestJwtIssuer();
    private static final UUID USER_ID = UUID.randomUUID();

    @DynamicPropertySource
    static void resourceServerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JWT_ISSUER::jwkSetUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestJwtIssuer.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> TestJwtIssuer.AUDIENCE);
    }

    @AfterAll
    static void stopIssuer() {
        JWT_ISSUER.close();
    }

    @Configuration
    @Import(SecurityConfig.class)
    static class TestApp {

        /**
         * Stands in for a real controller. Three endpoints, because the three things worth
         * proving are different: that authentication happens at all, that the {@code roles}
         * claim really becomes {@code ROLE_*} authorities, and that the principal name is
         * the user id every ownership check in this platform compares against.
         */
        @RestController
        static class ProbeController {

            @GetMapping("/api/v1/probe/authenticated")
            String authenticated(Authentication authentication) {
                return authentication.getName();
            }

            @GetMapping("/api/v1/probe/admin")
            @PreAuthorize("hasRole('ADMIN')")
            String adminOnly() {
                return "admin";
            }

            @GetMapping("/api/v1/probe/service")
            @PreAuthorize("hasRole('SERVICE')")
            String serviceOnly() {
                return "service";
            }
        }
    }

    @Autowired
    private MockMvc mockMvc;

    // --- tokens that must be accepted -------------------------------------------------

    @Test
    void aTokenSignedByThePublishedKeyAuthenticatesWithTheUserIdAsThePrincipal() throws Exception {
        mockMvc.perform(get("/api/v1/probe/authenticated")
                        .header("Authorization", bearer(JWT_ISSUER.token(USER_ID, "CUSTOMER"))))
                .andExpect(status().isOk())
                // Not the email, not the kid, not a generated name: every ownership check
                // in this platform compares authentication.getName() to a stored user id.
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .isEqualTo(USER_ID.toString()));
    }

    @Test
    void theRolesClaimBecomesRoleAuthorities() throws Exception {
        mockMvc.perform(get("/api/v1/probe/admin")
                        .header("Authorization", bearer(JWT_ISSUER.token(USER_ID, "ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void aServiceTokenReachesServiceOnlyEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/probe/service")
                        .header("Authorization", bearer(JWT_ISSUER.serviceToken("order-service"))))
                .andExpect(status().isOk());
    }

    @Test
    void anAuthenticatedTokenWithoutTheRequiredRoleGetsTheUnchangedForbiddenBody() throws Exception {
        mockMvc.perform(get("/api/v1/probe/admin")
                        .header("Authorization", bearer(JWT_ISSUER.token(USER_ID, "CUSTOMER"))))
                .andExpect(status().isForbidden())
                // The JSON shape clients already depend on (docs/security.md) is produced
                // by the same handlers as before -- the change of verification scheme is
                // not supposed to be visible from outside.
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(jsonPath("$.path").value("/api/v1/probe/admin"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    // --- tokens that must be rejected -------------------------------------------------

    /**
     * The whole point of the phase. This token is signed with the HMAC secret every
     * service used to hold, and it claims ADMIN. Before Phase 16 any service that could
     * verify a token could also produce this one.
     */
    @Test
    void aTokenSignedWithTheOldSharedHmacSecretIsRejected() throws Exception {
        expectUnauthorized(JWT_ISSUER.legacyHmacToken(USER_ID, "ADMIN"));
    }

    @Test
    void anUnsignedAlgNoneTokenIsRejected() throws Exception {
        expectUnauthorized(JWT_ISSUER.unsignedToken(USER_ID, "ADMIN"));
    }

    /** Correctly signed by a real RSA key -- one that is simply not in the JWKS. */
    @Test
    void aTokenSignedByAKeyThatIsNotInTheJwksIsRejected() throws Exception {
        expectUnauthorized(JWT_ISSUER.tokenSignedByAnUnpublishedKey(USER_ID, "ADMIN"));
    }

    @Test
    void anExpiredTokenIsRejected() throws Exception {
        expectUnauthorized(JWT_ISSUER.expiredToken(USER_ID, "CUSTOMER"));
    }

    @Test
    void aTokenFromAnotherIssuerIsRejected() throws Exception {
        expectUnauthorized(JWT_ISSUER.tokenFromAnotherIssuer(USER_ID, "CUSTOMER"));
    }

    /** Right issuer, right key, wrong system: audience is what stops the replay. */
    @Test
    void aTokenMintedForAnotherAudienceIsRejected() throws Exception {
        expectUnauthorized(JWT_ISSUER.tokenForAnotherAudience(USER_ID, "CUSTOMER"));
    }

    @Test
    void aTokenWhosePayloadWasEditedAfterSigningIsRejected() throws Exception {
        expectUnauthorized(JWT_ISSUER.tamperedToken(USER_ID));
    }

    @Test
    void aRequestWithNoTokenAtAllGetsTheUnchangedUnauthorizedBody() throws Exception {
        mockMvc.perform(get("/api/v1/probe/authenticated"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    /**
     * Every rejection above must land on the same 401 body as "no token at all" -- a
     * client should not be able to tell an expired token from a forged one, and
     * the body is the shape docs/security.md promises, now as an RFC 7807 problem
     * detail that still carries the original fields (ADR 009).
     */
    private void expectUnauthorized(String token) throws Exception {
        mockMvc.perform(get("/api/v1/probe/authenticated").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
