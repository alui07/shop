package dev.shop.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AuthIT;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class DeadLetterMetricsIT extends AuthIT {

    private static final Duration DEFAULT_STEP = Duration.ofMinutes(1);

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void removeParkedRows() {
        jdbc.sql("DELETE FROM event_publication_dead_letter").update();
    }

    @Test
    void theGaugeCountsParkedPublicationsAndReadsTheTableOncePerStep() {
        clock.advance(Duration.ofDays(2));
        double empty = rows();
        park();

        assertThat(rows()).as("served from the cache inside the step").isEqualTo(empty);

        clock.advance(DEFAULT_STEP);

        assertThat(rows()).isEqualTo(empty + 1);
    }

    private double rows() {
        return meters.get(DeadLetterMetrics.GAUGE_NAME).gauge().value();
    }

    private void park() {
        jdbc.sql("""
                        INSERT INTO event_publication_dead_letter (id, listener_id, event_type, serialized_event,
                            publication_date, moved_at)
                        VALUES (:id, 'listener', 'type', '{}', now(), now())
                        """).param("id", UUID.randomUUID()).update();
    }
}
