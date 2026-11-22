package dev.shop.api.web;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Publishes the size of the event dead letter table. The count is cached for one export step so scrapes and exports
 * never query the table more than once per step. A failed read is cached the same way and reports NaN, so an
 * unreachable database delays the export by one connection timeout per step at most, not on every read.
 */
@Component
class DeadLetterMetrics implements MeterBinder {

    static final String GAUGE_NAME = "shop.events.dead_letter.rows";

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration step;
    private Instant readAt;
    private double rows;

    DeadLetterMetrics(JdbcClient jdbc, Clock clock, @Value("${management.otlp.metrics.export.step:1m}") Duration step) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.step = step;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(GAUGE_NAME, this::rowCount)
                .description("Event publications parked after giving up; they need a person")
                .register(registry);
    }

    private synchronized double rowCount() {
        Instant now = clock.instant();
        if (readAt == null || !readAt.plus(step).isAfter(now)) {
            rows = readRows();
            readAt = now;
        }
        return rows;
    }

    private double readRows() {
        try {
            return jdbc.sql("SELECT count(*) FROM event_publication_dead_letter")
                    .query(Long.class)
                    .single();
        } catch (DataAccessException e) {
            return Double.NaN;
        }
    }
}
