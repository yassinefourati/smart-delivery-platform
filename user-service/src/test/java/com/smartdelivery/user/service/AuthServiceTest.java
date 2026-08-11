package com.smartdelivery.user.service;

import com.smartdelivery.user.domain.Role;
import com.smartdelivery.user.domain.RoleName;
import com.smartdelivery.user.domain.User;
import com.smartdelivery.user.dto.LoginRequest;
import com.smartdelivery.user.exception.InvalidCredentialsException;
import com.smartdelivery.user.repository.UserRepository;
import com.smartdelivery.user.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    private AuthService service() {
        return new AuthService(userRepository, passwordEncoder, jwtService);
    }

    private User activeUser() {
        User user = new User("jane@example.com", "hashed", "Jane", "Doe", null);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.addRole(new Role(RoleName.CUSTOMER));
        return user;
    }

    @Test
    void loginSucceedsWithCorrectPasswordAndReturnsToken() {
        AuthService authService = service();
        User user = activeUser();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct-password", user.getPasswordHash())).thenReturn(true);
        when(jwtService.generateToken(user)).thenReturn("signed.jwt.token");
        when(jwtService.getExpirationSeconds()).thenReturn(3600L);

        var response = authService.login(new LoginRequest(user.getEmail(), "correct-password"));

        assertThat(response.accessToken()).isEqualTo("signed.jwt.token");
        assertThat(response.userId()).isEqualTo(user.getId());
        assertThat(response.roles()).containsExactly("CUSTOMER");
    }

    @Test
    void loginFailsWithWrongPassword() {
        AuthService authService = service();
        User user = activeUser();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", user.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest(user.getEmail(), "wrong-password")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void loginFailsForDeactivatedAccountEvenWithCorrectPassword() {
        AuthService authService = service();
        User user = activeUser();
        ReflectionTestUtils.setField(user, "active", false);
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest(user.getEmail(), "correct-password")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void loginFailsForUnknownEmailWithoutRevealingWhichPartWasWrong() {
        AuthService authService = service();
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", "any-password")))
                .isInstanceOf(InvalidCredentialsException.class);
    }
}
