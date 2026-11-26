package dev.shop.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.ShopIT;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReservationOverdueMetricsIT extends ShopIT {

    private static final Duration STEP = Duration.ofMinutes(1);

    @Autowired
    private MeterRegistry meters;

    @AfterEach
    void removeOrders() {
        jdbc.sql(
                        "DELETE FROM reservation WHERE order_id IN (SELECT id FROM orders WHERE customer_subject = 'overdue-test')")
                .update();
        jdbc.sql("DELETE FROM orders WHERE customer_subject = 'overdue-test'").update();
    }

    @Test
    void onlyActiveReservationsOfOrdersExpiredMoreThanTwoMinutesAgoCount() {
        clock.advance(Duration.ofDays(2));
        double baseline = overdue();
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        reserve(now.minusMinutes(3), "ACTIVE");
        reserve(now.minusMinutes(3), "ACTIVE");
        reserve(now.minusMinutes(3), "RELEASED");
        reserve(now.minusSeconds(30), "ACTIVE");
        reserve(now.plusMinutes(5), "ACTIVE");

        assertThat(overdue()).as("cached for the step").isEqualTo(baseline);

        clock.advance(STEP);

        assertThat(overdue()).isEqualTo(baseline + 2);
    }

    private double overdue() {
        return meters.get(ReservationOverdueMetrics.GAUGE_NAME).gauge().value();
    }

    private void reserve(OffsetDateTime expiresAt, String status) {
        UUID order = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO orders (id, customer_subject, status, total_minor, currency, created_at, expires_at)
                        VALUES (:id, 'overdue-test', 'PENDING_PAYMENT', 0, 'USD', now(), :expires)
                        """).param("id", order).param("expires", expiresAt).update();
        jdbc.sql("""
                        INSERT INTO reservation (order_id, product_id, quantity, status, created_at)
                        VALUES (:order, :product, 1, :status, now())
                        """)
                .param("order", order)
                .param("product", lampId)
                .param("status", status)
                .update();
    }
}
