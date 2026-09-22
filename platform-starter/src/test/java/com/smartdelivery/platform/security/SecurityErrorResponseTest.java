package com.smartdelivery.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 401 and 403 bodies. These two handlers run inside the security filter chain,
 * before Spring MVC dispatch, so they never reach the {@code @RestControllerAdvice} and
 * have to produce the identical body themselves -- which is exactly the kind of "same
 * thing written twice" that drifts, and the reason both now go through
 * {@link com.smartdelivery.platform.web.ApiErrors}.
 */
class SecurityErrorResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void anUnauthenticatedRequestGetsA401ProblemDetail() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new JwtAuthenticationEntryPoint(objectMapper).commence(
                request("/api/v1/orders"), response, new BadCredentialsException("no token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        ObjectNode body = body(response);
        assertThat(body.path("error").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(body.path("status").asInt()).isEqualTo(401);
        assertThat(body.path("path").asText()).isEqualTo("/api/v1/orders");
        // Says nothing about *why* the token was unacceptable: a client must not be able
        // to tell an expired token from a forged one.
        assertThat(body.path("message").asText()).isEqualTo("Authentication is required to access this resource");
    }

    @Test
    void anUnauthorizedRequestGetsA403ProblemDetail() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new JwtAccessDeniedHandler(objectMapper).handle(
                request("/api/v1/users"), response, new AccessDeniedException("missing ROLE_ADMIN"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        ObjectNode body = body(response);
        assertThat(body.path("error").asText()).isEqualTo("FORBIDDEN");
        // Not "missing ROLE_ADMIN": which authority would have worked is not the caller's
        // business, and saying so maps out the authorization model for free.
        assertThat(body.path("message").asText()).isEqualTo("You do not have permission to perform this action");
    }

    /**
     * Before Phase 19 both handlers minted a fresh UUID here, so the id on a 401 or a 403
     * appeared in no log line anywhere -- useless on precisely the responses people ask
     * about most. Both now read the request's own id out of the MDC.
     */
    @Test
    void bothCarryTheRequestsOwnCorrelationId() throws Exception {
        MDC.put("correlationId", "the-request-id");

        MockHttpServletResponse unauthorized = new MockHttpServletResponse();
        new JwtAuthenticationEntryPoint(objectMapper).commence(
                request("/api/v1/orders"), unauthorized, new BadCredentialsException("no token"));

        MockHttpServletResponse forbidden = new MockHttpServletResponse();
        new JwtAccessDeniedHandler(objectMapper).handle(
                request("/api/v1/orders"), forbidden, new AccessDeniedException("nope"));

        assertThat(body(unauthorized).path("correlationId").asText()).isEqualTo("the-request-id");
        assertThat(body(forbidden).path("correlationId").asText()).isEqualTo("the-request-id");
    }

    @Test
    void bothStillProduceAnIdWhenNothingSetTheMdc() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new JwtAuthenticationEntryPoint(objectMapper).commence(
                request("/api/v1/orders"), response, new BadCredentialsException("no token"));

        assertThat(body(response).path("correlationId").asText()).isNotBlank();
    }

    private MockHttpServletRequest request(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }

    private ObjectNode body(MockHttpServletResponse response) throws Exception {
        return (ObjectNode) objectMapper.readTree(response.getContentAsString());
    }
}
