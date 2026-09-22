package com.smartdelivery.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves api-gateway's own code -- the path-based routing in application.yml and
 * {@link com.smartdelivery.gateway.observability.CorrelationIdGlobalFilter} -- actually
 * works end to end, rather than just the context-load smoke test in
 * {@link ApiGatewayApplicationTests}. Every downstream service URI is pointed at one
 * embedded stub HTTP server (JDK's own {@code com.sun.net.httpserver}, no new test
 * dependency, no Testcontainers/Docker needed) that records the request path and the
 * {@code X-Correlation-Id} header it received, so both routing and correlation
 * propagation are observable without standing up any real backend service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiGatewayRoutingIntegrationTest {

    private record CapturedRequest(String path, String correlationId) {
    }

    private static final BlockingQueue<CapturedRequest> CAPTURED = new LinkedBlockingQueue<>();
    private static final HttpServer STUB_SERVER = startStub();

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                CAPTURED.add(new CapturedRequest(
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("X-Correlation-Id")));
                byte[] body = "{\"stub\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void routeEveryDownstreamServiceToTheStub(DynamicPropertyRegistry registry) {
        String stubUri = "http://localhost:" + STUB_SERVER.getAddress().getPort();
        registry.add("USER_SERVICE_URI", () -> stubUri);
        registry.add("PRODUCT_SERVICE_URI", () -> stubUri);
        registry.add("INVENTORY_SERVICE_URI", () -> stubUri);
        registry.add("ORDER_SERVICE_URI", () -> stubUri);
        registry.add("PAYMENT_SERVICE_URI", () -> stubUri);
        registry.add("DELIVERY_SERVICE_URI", () -> stubUri);
    }

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        CAPTURED.clear();
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private CapturedRequest awaitCapturedRequest() throws InterruptedException {
        CapturedRequest captured = CAPTURED.poll(5, TimeUnit.SECONDS);
        assertThat(captured).as("stub server received a proxied request").isNotNull();
        return captured;
    }

    @Test
    void routesProductPathsToProductService() throws InterruptedException {
        UUID productId = UUID.randomUUID();

        client.get().uri("/api/v1/products/" + productId).exchange().expectStatus().isOk();

        assertThat(awaitCapturedRequest().path()).isEqualTo("/api/v1/products/" + productId);
    }

    /**
     * Both paths inventory-service actually serves, because only one of them was routed
     * until Phase 18: {@code /api/v1/warehouses/**} was missing, so warehouse management
     * was unreachable for any client coming through the front door. A route that exists
     * for five of six services is the kind of gap that survives review precisely because
     * nothing looks wrong.
     */
    @Test
    void routesBothInventoryAndWarehousePathsToInventoryService() throws InterruptedException {
        UUID productId = UUID.randomUUID();

        client.get().uri("/api/v1/inventory/" + productId).exchange().expectStatus().isOk();
        assertThat(awaitCapturedRequest().path()).isEqualTo("/api/v1/inventory/" + productId);

        client.get().uri("/api/v1/warehouses").exchange().expectStatus().isOk();
        assertThat(awaitCapturedRequest().path()).isEqualTo("/api/v1/warehouses");
    }

    /** The JWKS is public by design (ADR 007) and has to be reachable through the gateway. */
    @Test
    void routesTheJwksEndpointToUserService() throws InterruptedException {
        client.get().uri("/.well-known/jwks.json").exchange().expectStatus().isOk();

        assertThat(awaitCapturedRequest().path()).isEqualTo("/.well-known/jwks.json");
    }

    @Test
    void routesOrderPathsToOrderService() throws InterruptedException {
        UUID orderId = UUID.randomUUID();

        client.get().uri("/api/v1/orders/" + orderId).exchange().expectStatus().isOk();

        assertThat(awaitCapturedRequest().path()).isEqualTo("/api/v1/orders/" + orderId);
    }

    @Test
    void generatesACorrelationIdWhenTheClientDoesNotSendOne() throws InterruptedException {
        client.get().uri("/api/v1/products/" + UUID.randomUUID())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Correlation-Id");

        CapturedRequest captured = awaitCapturedRequest();
        assertThat(captured.correlationId()).isNotBlank();
    }

    @Test
    void forwardsAClientSuppliedCorrelationIdUnchanged() throws InterruptedException {
        String correlationId = "test-correlation-" + UUID.randomUUID();

        client.get().uri("/api/v1/products/" + UUID.randomUUID())
                .header("X-Correlation-Id", correlationId)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Correlation-Id", correlationId);

        assertThat(awaitCapturedRequest().correlationId()).isEqualTo(correlationId);
    }
}
