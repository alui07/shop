package dev.shop.api.web;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
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
    private final StepCachedReading rows;

    DeadLetterMetrics(JdbcClient jdbc, Clock clock, @Value("${management.otlp.metrics.export.step:1m}") Duration step) {
        this.jdbc = jdbc;
        this.rows = new StepCachedReading(clock, step, this::readRows);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(GAUGE_NAME, rows::value)
                .description("Event publications parked after giving up; they need a person")
                .register(registry);
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
