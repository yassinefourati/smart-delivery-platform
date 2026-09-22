package com.smartdelivery.platform.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The shared half of every service's error handling, driven through a real MVC dispatch
 * rather than by calling the handler methods directly -- most of these exceptions are
 * thrown by Spring itself during argument binding, so invoking the methods by hand would
 * prove the mapping exists without proving anything ever reaches it.
 */
class PlatformExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new ProbeExceptionHandler())
                // standaloneSetup only wires a validator when one is discoverable; set it
                // explicitly so @Valid really runs and the 400 below is Spring's, not the
                // test's own arrangement.
                .setValidator(validator)
                .build();
    }

    @Test
    void validationFailuresNameEveryOffendingField() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("name")));
    }

    /**
     * Answered 500 before Phase 19, because nothing mapped it and the catch-all took it.
     * The message must not echo the body back: Jackson's own parse errors quote the
     * offending input, which is a neat way to reflect an attacker's payload.
     */
    @Test
    void malformedJsonIsABadRequestAndDoesNotEchoTheBody() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\": <script>}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("script"))));
    }

    @Test
    void anUnparseablePathVariableIsABadRequest() throws Exception {
        mockMvc.perform(get("/probe/id/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void theWrongHttpMethodIs405() throws Exception {
        mockMvc.perform(get("/probe/body"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void anAuthenticationFailureIs401() throws Exception {
        mockMvc.perform(get("/probe/unauthenticated"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void anAuthorizationFailureIs403AndSaysNothingAboutWhy() throws Exception {
        mockMvc.perform(get("/probe/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("You do not have permission to perform this action"));
    }

    /** The catch-all must never put the exception's own message on the wire. */
    @Test
    void anUnexpectedFailureIs500WithNoDetail() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    /** Every response carries the request's own path, whichever handler produced it. */
    @Test
    void everyBodyCarriesTheRequestPath() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(jsonPath("$.path").value("/probe/boom"))
                .andExpect(jsonPath("$.instance").value("/probe/boom"));
    }

    /**
     * A subclass may map its own exceptions and must win over the inherited catch-all.
     * This is the extension point every service's GlobalExceptionHandler relies on.
     */
    @Test
    void aSubclassHandlerWinsOverTheInheritedCatchAll() throws Exception {
        mockMvc.perform(get("/probe/domain"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    // --- fixtures ---------------------------------------------------------------------

    record ProbeRequest(@NotBlank String name) {
    }

    static class ProbeNotFoundException extends RuntimeException {
        ProbeNotFoundException(String message) {
            super(message);
        }
    }

    @RestController
    static class ProbeController {

        @PostMapping("/probe/body")
        String body(@Valid @RequestBody ProbeRequest request) {
            return request.name();
        }

        @GetMapping("/probe/id/{id}")
        String byId(@PathVariable UUID id) {
            return id.toString();
        }

        @GetMapping("/probe/unauthenticated")
        String unauthenticated() {
            throw new BadCredentialsException("nope");
        }

        @GetMapping("/probe/forbidden")
        String forbidden() {
            throw new AccessDeniedException("the reason must not reach the client");
        }

        @GetMapping("/probe/boom")
        String boom() {
            throw new IllegalStateException("a database password, probably");
        }

        @GetMapping("/probe/domain")
        String domain() {
            throw new ProbeNotFoundException("probe 7 not found");
        }
    }

    /** Stands in for a service's own GlobalExceptionHandler. */
    @RestControllerAdvice
    static class ProbeExceptionHandler extends PlatformExceptionHandler {

        @org.springframework.web.bind.annotation.ExceptionHandler(ProbeNotFoundException.class)
        org.springframework.http.ResponseEntity<org.springframework.http.ProblemDetail> handleNotFound(
                ProbeNotFoundException ex, jakarta.servlet.http.HttpServletRequest request) {
            return build(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request);
        }
    }
}
