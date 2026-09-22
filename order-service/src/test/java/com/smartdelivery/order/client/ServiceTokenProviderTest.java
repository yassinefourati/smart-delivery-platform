package com.smartdelivery.order.client;

import com.smartdelivery.order.exception.ServiceTokenUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

/**
 * The caching contract order-service depends on (ADR 007). Getting this wrong is not
 * obvious from the outside: fetching per call still works, it just makes user-service a
 * synchronous dependency of every single saga step, and expiring a token mid-flight
 * still works, it just fails one order in a way nothing retries at this level.
 */
class ServiceTokenProviderTest {

    private static final String TOKEN_URI = "http://user-service.test/api/v1/auth/service-token";

    private RestClient.Builder builder;
    private MockRestServiceServer userService;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        userService = MockRestServiceServer.bindTo(builder).build();
    }

    private ServiceTokenProvider provider() {
        return new ServiceTokenProvider(builder,
                new UserServiceProperties("http://user-service.test", "order-service", "the-secret"));
    }

    private void expectTokenRequest(String token, long expiresInSeconds) {
        userService.expect(once(), requestTo(TOKEN_URI))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.clientId").value("order-service"))
                .andExpect(jsonPath("$.clientSecret").value("the-secret"))
                .andRespond(withSuccess("""
                        {"accessToken":"%s","tokenType":"Bearer","expiresInSeconds":%d}
                        """.formatted(token, expiresInSeconds), MediaType.APPLICATION_JSON));
    }

    @Test
    void fetchesATokenByPresentingItsOwnClientCredentials() {
        expectTokenRequest("signed.service.token", 300);

        assertThat(provider().currentToken()).isEqualTo("signed.service.token");
        userService.verify();
    }

    @Test
    void reusesTheCachedTokenInsteadOfFetchingOncePerCall() {
        expectTokenRequest("signed.service.token", 300);
        ServiceTokenProvider provider = provider();

        for (int call = 0; call < 5; call++) {
            assertThat(provider.currentToken()).isEqualTo("signed.service.token");
        }

        // once() above is the assertion: a sixth request would fail the mock server.
        userService.verify();
    }

    /**
     * A token with less than the refresh skew left is treated as already stale, so a
     * saga step never picks one up and then has it expire before the downstream call
     * lands.
     */
    @Test
    void refreshesAheadOfExpiryRatherThanAtIt() {
        expectTokenRequest("nearly-expired", 20);
        expectTokenRequest("fresh", 300);
        ServiceTokenProvider provider = provider();

        assertThat(provider.currentToken()).isEqualTo("nearly-expired");
        assertThat(provider.currentToken()).isEqualTo("fresh");
        userService.verify();
    }

    /**
     * A brief user-service outage must not fail orders that are holding a perfectly
     * valid credential -- the refresh window exists precisely so there is something to
     * fall back on.
     */
    @Test
    void keepsUsingAStillValidTokenWhenARefreshFails() {
        expectTokenRequest("nearly-expired", 20);
        userService.expect(once(), requestTo(TOKEN_URI)).andRespond(withServerError());
        ServiceTokenProvider provider = provider();

        assertThat(provider.currentToken()).isEqualTo("nearly-expired");
        assertThat(provider.currentToken()).isEqualTo("nearly-expired");
        userService.verify();
    }

    @Test
    void failsWithADistinctExceptionWhenThereIsNoTokenAndNoneCanBeFetched() {
        userService.expect(once(), requestTo(TOKEN_URI)).andRespond(withServerError());

        // Distinct, because it is raised inside the inventory-service and
        // payment-service circuit breakers and says nothing about their health -- both
        // configure it as an ignored exception. See ADR 007 and docs/resilience.md.
        assertThatThrownBy(() -> provider().currentToken())
                .isInstanceOf(ServiceTokenUnavailableException.class)
                .hasMessageContaining("user-service");
    }

    @Test
    void rejectedCredentialsFailTheSameWayAnOutageDoes() {
        userService.expect(once(), requestTo(TOKEN_URI)).andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> provider().currentToken())
                .isInstanceOf(ServiceTokenUnavailableException.class);
    }
}
