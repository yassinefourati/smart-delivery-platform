package com.smartdelivery.user.security;

import com.nimbusds.jwt.SignedJWT;
import com.smartdelivery.user.domain.Role;
import com.smartdelivery.user.domain.RoleName;
import com.smartdelivery.user.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyPair;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Asserts against a real {@link JwtDecoder} built by {@link JwtDecoderConfig} rather than
 * against the token's own text, so these tests prove the thing that actually matters: a
 * token this service issues is one a resource server configured the platform's way will
 * accept -- and one signed by anything else is not.
 */
class JwtServiceTest {

    private final JwtDecoderConfig decoderConfig = new JwtDecoderConfig();

    @Test
    void issuesAnRs256TokenCarryingTheKeyIdThatSignedIt() throws Exception {
        JwtProperties properties = JwtTestFixtures.properties();
        JwtKeyProvider keyProvider = new JwtKeyProvider(properties);

        String token = new JwtService(keyProvider, properties).generateToken(customer());

        SignedJWT parsed = SignedJWT.parse(token);
        assertThat(parsed.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        // Without a kid a resource server holding two published keys would have to try
        // both, and rotation would stop being a configuration change.
        assertThat(parsed.getHeader().getKeyID()).isEqualTo(JwtTestFixtures.KID);
    }

    @Test
    void aUserTokenCarriesTheClaimsEveryResourceServerChecksOrReads() {
        JwtProperties properties = JwtTestFixtures.properties();
        JwtKeyProvider keyProvider = new JwtKeyProvider(properties);
        User user = customer();

        String token = new JwtService(keyProvider, properties).generateToken(user);
        Jwt decoded = decoder(keyProvider, properties).decode(token);

        assertThat(decoded.getSubject()).isEqualTo(user.getId().toString());
        assertThat(decoded.getIssuer()).hasToString(JwtTestFixtures.ISSUER);
        assertThat(decoded.getAudience()).containsExactly(JwtTestFixtures.AUDIENCE);
        assertThat(decoded.getClaimAsStringList("roles")).containsExactly("CUSTOMER");
        assertThat(decoded.getClaimAsString("email")).isEqualTo(user.getEmail());
        assertThat(decoded.getExpiresAt()).isNotNull();
    }

    @Test
    void aServiceTokenIsSubjectedToItsClientIdAndCarriesOnlyTheServiceRole() {
        JwtProperties properties = JwtTestFixtures.properties();
        JwtKeyProvider keyProvider = new JwtKeyProvider(properties);

        String token = new JwtService(keyProvider, properties).generateServiceToken("order-service");
        Jwt decoded = decoder(keyProvider, properties).decode(token);

        // Subject is the client id, not a user id -- which is what makes a SERVICE token
        // distinguishable from a human's in a log or a trace.
        assertThat(decoded.getSubject()).isEqualTo("order-service");
        assertThat(decoded.getClaimAsStringList("roles")).containsExactly("SERVICE");
        assertThat(decoded.getClaimAsString("email")).isNull();
    }

    @Test
    void aTokenSignedByAnotherKeyIsRejected() {
        JwtProperties properties = JwtTestFixtures.properties();
        JwtKeyProvider ourKeys = new JwtKeyProvider(properties);
        // A second provider with no configured key generates its own pair, which is
        // precisely the "someone else's signing key" case.
        JwtKeyProvider someoneElsesKeys = new JwtKeyProvider(properties);

        String foreignToken = new JwtService(someoneElsesKeys, properties).generateToken(customer());

        assertThatThrownBy(() -> decoder(ourKeys, properties).decode(foreignToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void aTokenSignedByARetiredKeyStillVerifiesWhileItIsPublished() {
        KeyPair retiredPair = JwtKeyProviderTest.rsaKeyPair();
        JwtProperties issuingProperties = JwtTestFixtures.properties(
                new JwtProperties.SigningKey("retired", JwtKeyProviderTest.privateKeyPem(retiredPair), ""));
        String tokenFromTheOldKey =
                new JwtService(new JwtKeyProvider(issuingProperties), issuingProperties).generateToken(customer());

        // Now rotate: a new active key, the old one moved to retired-keys.
        JwtProperties afterRotation = JwtTestFixtures.properties(
                new JwtProperties.SigningKey("active", JwtKeyProviderTest.privateKeyPem(JwtKeyProviderTest.rsaKeyPair()), ""),
                List.of(new JwtProperties.RetiredKey("retired", JwtKeyProviderTest.publicKeyPem(retiredPair))));

        Jwt decoded = decoder(new JwtKeyProvider(afterRotation), afterRotation).decode(tokenFromTheOldKey);

        assertThat(decoded.getSubject()).isNotBlank();
    }

    private JwtDecoder decoder(JwtKeyProvider keyProvider, JwtProperties properties) {
        return decoderConfig.jwtDecoder(keyProvider, properties);
    }

    private User customer() {
        User user = new User("test@example.com", "hash", "Test", "User", null);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.addRole(new Role(RoleName.CUSTOMER));
        return user;
    }
}
