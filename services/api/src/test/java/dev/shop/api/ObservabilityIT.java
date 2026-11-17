package dev.shop.api;

import static dev.shop.api.support.HttpGet.get;
import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.IntegrationTest;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityIT {

    private static final String TRACE_ID_FIELD = "traceId";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    @Test
    void requestLogLineCarriesTheTraceIdOfTheIncomingTraceparent(CapturedOutput output) {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

        get(port, "/api/categories", Map.of("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01"));

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(requestLogLine(output, traceId)).isPresent());
        assertThat(requestLogLine(output, traceId)
                        .orElseThrow()
                        .get(TRACE_ID_FIELD)
                        .asString())
                .isEqualTo(traceId);
    }

    @Test
    void doesNotExportMetricsTracesOrLogsOverOtlp() {
        assertThat(context.getBeansOfType(OtlpMeterRegistry.class)).isEmpty();
        assertThat(context.getBeansOfType(SpanExporter.class)).isEmpty();
        assertThat(context.getBeansOfType(LogRecordExporter.class)).isEmpty();
    }

    private static Optional<JsonNode> requestLogLine(CapturedOutput output, String traceId) {
        return output.getAll()
                .lines()
                .filter(line -> line.startsWith("{") && line.contains(traceId) && line.contains("/api/categories"))
                .map(JSON::readTree)
                .findFirst();
    }
}
