package com.smartdelivery.user.security;

import com.smartdelivery.user.domain.Role;
import com.smartdelivery.user.domain.RoleName;
import com.smartdelivery.user.domain.User;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-key-must-be-at-least-32-bytes-long";

    @Test
    void generatesTokenAndParsesItsOwnClaims() {
        JwtService jwtService = new JwtService(SECRET, 60_000);
        User user = userWithId();
        user.addRole(new Role(RoleName.CUSTOMER));

        String token = jwtService.generateToken(user);
        Claims claims = jwtService.parseClaims(token).orElseThrow();

        assertThat(claims.getSubject()).isEqualTo(user.getId().toString());
        assertThat(claims.get("email")).isEqualTo(user.getEmail());
        assertThat(jwtService.extractRoles(claims)).containsExactly("CUSTOMER");
    }

    @Test
    void rejectsTokenSignedWithADifferentSecret() {
        JwtService issuer = new JwtService(SECRET, 60_000);
        JwtService verifier = new JwtService("a-completely-different-secret-key-of-32-bytes!!", 60_000);

        String token = issuer.generateToken(userWithId());

        assertThat(verifier.parseClaims(token)).isEmpty();
    }

    @Test
    void rejectsExpiredToken() {
        JwtService jwtService = new JwtService(SECRET, -1_000);

        String token = jwtService.generateToken(userWithId());

        assertThat(jwtService.parseClaims(token)).isEmpty();
    }

    @Test
    void rejectsMalformedToken() {
        JwtService jwtService = new JwtService(SECRET, 60_000);

        assertThat(jwtService.parseClaims("not-a-jwt")).isEmpty();
    }

    private User userWithId() {
        User user = new User("test@example.com", "hash", "Test", "User", null);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }
}
