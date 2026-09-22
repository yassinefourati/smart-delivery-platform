package com.smartdelivery.order.client;

import com.smartdelivery.order.exception.ServiceTokenUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Obtains and caches the short-lived {@code SERVICE}-role token order-service presents to
 * inventory-service and payment-service for the saga's REST calls (ADR 007).
 *
 * This replaces {@code InternalServiceTokenProvider}, which minted its own tokens with
 * the platform-wide HMAC secret. The behavior looks the same from the outside and the
 * security property is entirely different: order-service can no longer sign anything.
 * It asks user-service, authenticating with a credential that is order-service's alone,
 * and user-service decides.
 *
 * <h2>Caching</h2>
 * A token lasts minutes and a saga step takes milliseconds, so fetching one per call
 * would turn every reservation into two network round trips and make user-service a
 * synchronous dependency of every order. The cached token is reused until it is within
 * {@link #REFRESH_SKEW} of expiring, at which point the next caller refreshes it.
 *
 * Two deliberate properties of that cache. A refresh that fails while the current token
 * is still valid returns the current token rather than propagating: a brief user-service
 * blip should not fail orders that have a perfectly good credential in hand. And the
 * refresh is not locked: two threads arriving at the same moment may both fetch, which
 * costs one redundant call and avoids every saga thread queueing behind one HTTP request.
 *
 * <h2>Why no Resilience4j here</h2>
 * The saga's downstream clients keep exactly the circuit breaker, retry, bulkhead, and
 * rate limiter they had (docs/resilience.md) -- untouched by this phase. This fetch
 * deliberately gets none of its own: a failure propagates as
 * {@link ServiceTokenUnavailableException} into the same Kafka retry and dead-letter path
 * that already handles every other infrastructure failure in a saga step, which is the
 * codebase's existing answer to "what if a dependency is down" and needs no second one.
 * What it *does* need is for that failure not to be misread: it is raised inside the
 * downstream clients' breakers, so both list it as an ignored exception, exactly as they
 * already ignore InsufficientStockException. user-service being unreachable is not
 * evidence that inventory-service is unhealthy.
 */
@Component
public class ServiceTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenProvider.class);

    /** Refresh this far ahead of expiry, so a token never expires mid-flight. */
    private static final Duration REFRESH_SKEW = Duration.ofSeconds(30);

    private final RestClient restClient;
    private final UserServiceProperties properties;
    private final AtomicReference<CachedToken> cached = new AtomicReference<>();

    public ServiceTokenProvider(RestClient.Builder restClientBuilder, UserServiceProperties properties) {
        this.restClient = restClientBuilder.baseUrl(properties.baseUrl()).build();
        this.properties = properties;
    }

    public String currentToken() {
        CachedToken current = cached.get();
        if (current != null && current.isUsableAt(Instant.now())) {
            return current.token();
        }

        try {
            CachedToken fetched = fetch();
            cached.set(fetched);
            return fetched.token();
        } catch (RestClientException e) {
            if (current != null && current.isValidAt(Instant.now())) {
                log.warn("Could not refresh the service token; reusing the cached one, which expires at {}",
                        current.expiresAt(), e);
                return current.token();
            }
            throw new ServiceTokenUnavailableException(
                    "Could not obtain a service token from user-service", e);
        }
    }

    private CachedToken fetch() {
        Instant requestedAt = Instant.now();
        ServiceTokenResponse response = restClient.post()
                .uri("/api/v1/auth/service-token")
                .body(Map.of("clientId", properties.clientId(), "clientSecret", properties.clientSecret()))
                .retrieve()
                .body(ServiceTokenResponse.class);

        if (response == null || response.accessToken() == null) {
            // Thrown as a RestClientException rather than directly as the outer type so
            // that a 200 with a useless body takes the same path as an outright failure,
            // cached-token fallback included.
            throw new RestClientException(
                    "user-service returned no access token for client " + properties.clientId());
        }
        // Measured from before the request, not after: erring towards refreshing early
        // costs one extra call, erring late costs a 401 in the middle of a saga step.
        return new CachedToken(response.accessToken(), requestedAt.plusSeconds(response.expiresInSeconds()));
    }

    private record CachedToken(String token, Instant expiresAt) {

        boolean isUsableAt(Instant now) {
            return now.isBefore(expiresAt.minus(REFRESH_SKEW));
        }

        boolean isValidAt(Instant now) {
            return now.isBefore(expiresAt);
        }
    }

    /** Only the two fields this service needs; user-service may add more (ADR 002's tolerant-reader rule). */
    record ServiceTokenResponse(String accessToken, String tokenType, long expiresInSeconds) {
    }
}
