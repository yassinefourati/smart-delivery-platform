package com.smartdelivery.user.service;

import com.smartdelivery.user.dto.ServiceTokenRequest;
import com.smartdelivery.user.dto.ServiceTokenResponse;
import com.smartdelivery.user.exception.InvalidServiceClientException;
import com.smartdelivery.user.security.JwtProperties;
import com.smartdelivery.user.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceTokenServiceTest {

    @Mock
    private JwtService jwtService;

    private final JwtProperties properties = new JwtProperties(
            "https://user-service.test", "smart-delivery-platform",
            Duration.ofHours(1), Duration.ofMinutes(5),
            new JwtProperties.SigningKey("kid", "", ""), List.of(),
            Map.of("order-service", "the-real-secret"));

    private ServiceTokenService service() {
        return new ServiceTokenService(properties, jwtService);
    }

    @Test
    void issuesATokenToAClientThatPresentsItsOwnSecret() {
        when(jwtService.generateServiceToken("order-service")).thenReturn("signed.service.token");
        when(jwtService.getServiceExpirationSeconds()).thenReturn(300L);

        ServiceTokenResponse response = service().issue(new ServiceTokenRequest("order-service", "the-real-secret"));

        assertThat(response.accessToken()).isEqualTo("signed.service.token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresInSeconds()).isEqualTo(300L);
    }

    @Test
    void rejectsAWrongSecret() {
        assertThatThrownBy(() -> service().issue(new ServiceTokenRequest("order-service", "guessed")))
                .isInstanceOf(InvalidServiceClientException.class);

        verify(jwtService, never()).generateServiceToken("order-service");
    }

    @Test
    void rejectsAnUnknownClientId() {
        assertThatThrownBy(() -> service().issue(new ServiceTokenRequest("inventory-service", "the-real-secret")))
                .isInstanceOf(InvalidServiceClientException.class);
    }

    /**
     * Both failures produce the same exception and the same message. Saying which half
     * was wrong would tell a caller which client ids exist.
     */
    @Test
    void doesNotRevealWhetherItWasTheClientIdOrTheSecretThatWasWrong() {
        Throwable wrongSecret = org.assertj.core.api.Assertions.catchThrowable(
                () -> service().issue(new ServiceTokenRequest("order-service", "guessed")));
        Throwable unknownClient = org.assertj.core.api.Assertions.catchThrowable(
                () -> service().issue(new ServiceTokenRequest("nobody", "guessed")));

        assertThat(wrongSecret).hasMessage(unknownClient.getMessage());
    }
}
