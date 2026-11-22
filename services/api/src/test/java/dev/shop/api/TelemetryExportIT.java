package dev.shop.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.shop.api.support.AdminClient;
import dev.shop.api.support.PaymentShopIT;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * All three signals leave the application over OTLP/HTTP when switched on. The endpoint, service name and resource
 * attributes come from the OTEL_* variables the failsafe plugin sets (the same ones the stacks set), so the test
 * proves the environment mapping as well as the exporters.
 */
@DirtiesContext
@TestPropertySource(
        properties = {
            "management.tracing.export.otlp.enabled=true",
            "management.otlp.metrics.export.enabled=true",
            "management.logging.export.otlp.enabled=true",
            "management.otlp.metrics.export.step=1s",
            "management.otlp.metrics.export.base-time-unit=seconds"
        })
class TelemetryExportIT extends PaymentShopIT {

    private static final int OTLP_PORT = 14318; // matches OTEL_EXPORTER_OTLP_ENDPOINT in the pom
    private static final Logger LOGGER = LoggerFactory.getLogger(TelemetryExportIT.class);
    private static final Map<String, List<String>> RECEIVED = new ConcurrentHashMap<>();

    @RegisterExtension
    static final CollectorStub COLLECTOR = new CollectorStub();

    @Autowired
    private MeterRegistry meters;

    /**
     * The root store closes its resources at the end of the whole test run, after Spring's context close has flushed
     * the exporters; stopping the stub in afterAll would make that flush fail with connection refused.
     */
    static final class CollectorStub implements BeforeAllCallback {
        @Override
        public void beforeAll(ExtensionContext context) throws IOException {
            HttpServer server =
                    HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), OTLP_PORT), 0);
            server.createContext("/", TelemetryExportIT::record);
            server.start();
            context.getRoot().getStore(ExtensionContext.Namespace.GLOBAL).put("otlp-collector-stub", (AutoCloseable)
                    () -> server.stop(0));
        }
    }

    @Test
    void tracesMetricsAndLogsAreExportedWithoutThePaymentToken() {
        AdminClient client = customerWithEmail("telemetry-payer", "telemetry@example.test");
        String orderId = placeOrderFor(client, LAMP, 1).get("id").asString();
        String token = tokenOf(startPayment(client, orderId));
        ok(client.get("/api/fake-psp/payments/" + token));
        String logMarker = "telemetry-marker-" + UUID.randomUUID();
        LOGGER.info(logMarker);

        awaitRequestCarrying("/v1/traces", "/api/fake-psp/payments/{token}");
        awaitRequestCarrying("/v1/metrics", "http.server.requests");
        awaitRequestCarrying("/v1/logs", logMarker);

        for (String path : List.of("/v1/traces", "/v1/metrics", "/v1/logs")) {
            assertThat(bodies(path)).as(path).noneMatch(body -> body.contains(token));
        }
        assertThat(meters.get("http.server.requests").timer().takeSnapshot().histogramCounts())
                .as("latency buckets, so histogram_quantile has bounds to work with")
                .isNotEmpty();
        assertThat(bodies("/v1/metrics"))
                .anyMatch(body -> body.contains("shop.events.dead_letter.rows"))
                .anyMatch(body -> body.contains("shop.reservations.released.units"));
        for (String path : List.of("/v1/traces", "/v1/metrics", "/v1/logs")) {
            assertThat(bodies(path))
                    .as(path)
                    .anyMatch(body -> body.contains("shop-api-it") && body.contains("deployment.environment"));
        }
    }

    private static void record(HttpExchange exchange) throws IOException {
        String encoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
        try (InputStream raw = exchange.getRequestBody();
                InputStream body = "gzip".equals(encoding) ? new GZIPInputStream(raw) : raw) {
            RECEIVED.computeIfAbsent(exchange.getRequestURI().getPath(), path -> new CopyOnWriteArrayList<>())
                    .add(new String(body.readAllBytes(), StandardCharsets.ISO_8859_1));
        }
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    private static List<String> bodies(String path) {
        return RECEIVED.getOrDefault(path, List.of());
    }

    private static void awaitRequestCarrying(String path, String text) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(bodies(path)).anyMatch(body -> body.contains(text)));
    }
}
