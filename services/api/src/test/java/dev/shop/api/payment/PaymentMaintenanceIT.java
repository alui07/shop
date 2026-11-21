package dev.shop.api.payment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.PaymentShopIT;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PaymentMaintenanceIT extends PaymentShopIT {

    @Autowired
    private PaymentMaintenanceJob job;

    @Test
    void recordsOlderThanAWeekAreDeletedInBatchesAndNewerOnesStay() {
        OffsetDateTime old =
                OffsetDateTime.now(clock).minus(PaymentMaintenanceJob.RETENTION).minusMinutes(1);
        OffsetDateTime recent = OffsetDateTime.now(clock).minusDays(1);
        for (int i = 0; i < PaymentMaintenanceJob.BATCH + 5; i++) {
            insertEvent("old-" + i, old);
        }
        insertEvent("recent", recent);
        jdbc.sql(
                        "INSERT INTO payment_webhook_unmatched (event_id, provider_payment_id, received_at) VALUES ('u1', 'p', :at)")
                .param("at", old)
                .update();
        jdbc.sql(
                        "INSERT INTO payment_webhook_unmatched (event_id, provider_payment_id, received_at) VALUES ('u2', 'p', :at)")
                .param("at", recent)
                .update();

        int deleted = job.runOnce();

        assertThat(deleted).isEqualTo(PaymentMaintenanceJob.BATCH + 5 + 1);
        assertThat(count("payment_webhook_event")).isEqualTo(1);
        assertThat(count("payment_webhook_unmatched")).isEqualTo(1);
        assertThat(job.runOnce()).isZero();
        clock.advance(Duration.ofDays(8));
        assertThat(job.runOnce()).isEqualTo(2);
    }

    private void insertEvent(String id, OffsetDateTime at) {
        jdbc.sql("INSERT INTO payment_webhook_event (event_id, type, received_at) VALUES (:id, 'payment.failed', :at)")
                .param("id", id)
                .param("at", at)
                .update();
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }
}
