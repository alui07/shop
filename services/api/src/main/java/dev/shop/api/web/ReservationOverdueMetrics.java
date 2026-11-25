package dev.shop.api.web;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Counts reservations still holding stock although their order expired more than {@link #GRACE} ago, about four runs of
 * the expiry job. The job's own timer shows that it runs; this shows that it frees what it should. Read once per export
 * step; a failed read reports NaN so a database outage does not look like zero overdue.
 */
@Component
class ReservationOverdueMetrics implements MeterBinder {

    static final String GAUGE_NAME = "shop.reservations.overdue";
    static final Duration GRACE = Duration.ofMinutes(2);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final StepCachedReading overdue;

    ReservationOverdueMetrics(
            JdbcClient jdbc, Clock clock, @Value("${management.otlp.metrics.export.step:1m}") Duration step) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.overdue = new StepCachedReading(clock, step, this::readOverdue);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(GAUGE_NAME, overdue::value)
                .description("Active reservations whose order expired more than two minutes ago")
                .register(registry);
    }

    private double readOverdue() {
        try {
            return jdbc.sql("""
                            SELECT count(*) FROM reservation r JOIN orders o ON o.id = r.order_id
                            WHERE r.status = 'ACTIVE' AND o.expires_at < :cutoff
                            """)
                    .param("cutoff", clock.instant().minus(GRACE).atOffset(ZoneOffset.UTC))
                    .query(Long.class)
                    .single();
        } catch (DataAccessException e) {
            return Double.NaN;
        }
    }
}
