package dev.shop.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.shop.api.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

class DeadLetterMetricsTest {

    private static final Duration STEP = Duration.ofSeconds(10);

    private final JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    private final MutableClock clock = new MutableClock();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void anUnreachableDatabaseReadsAsNaNAndIsNotAskedAgainWithinTheStep() {
        when(jdbc.sql(anyString()).query(Long.class).single())
                .thenThrow(new DataAccessResourceFailureException("database down"))
                .thenReturn(3L);
        new DeadLetterMetrics(jdbc, clock, STEP).bindTo(registry);

        assertThat(rows()).isNaN();
        assertThat(rows())
                .as("the failure is cached, so the second read does not reach the 3L")
                .isNaN();

        clock.advance(STEP);

        assertThat(rows()).isEqualTo(3.0);
    }

    private double rows() {
        return registry.get(DeadLetterMetrics.GAUGE_NAME).gauge().value();
    }
}
