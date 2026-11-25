package dev.shop.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

class ReservationOverdueMetricsTest {

    private static final Duration STEP = Duration.ofSeconds(10);

    private final JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    private final MutableClock clock = new MutableClock();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void anUnreachableDatabaseReadsAsNaNNotZeroAndIsNotAskedAgainWithinTheStep() {
        when(jdbc.sql(anyString()).param(anyString(), any()).query(Long.class).single())
                .thenThrow(new DataAccessResourceFailureException("database down"))
                .thenReturn(2L);
        new ReservationOverdueMetrics(jdbc, clock, STEP).bindTo(registry);

        assertThat(overdue()).isNaN();
        assertThat(overdue()).isNaN();

        clock.advance(STEP);

        assertThat(overdue()).isEqualTo(2.0);
    }

    private double overdue() {
        return registry.get(ReservationOverdueMetrics.GAUGE_NAME).gauge().value();
    }
}
