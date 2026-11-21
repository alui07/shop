package dev.shop.api.payment;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Webhook dedupe and unmatched-event records only matter while the provider can still retry; a week is plenty. */
@Component
class PaymentMaintenanceJob {

    static final Duration RETENTION = Duration.ofDays(7);
    static final int BATCH = 1000;

    private static final Logger LOGGER = LoggerFactory.getLogger(PaymentMaintenanceJob.class);

    private final PaymentRepository payments;
    private final Clock clock;

    PaymentMaintenanceJob(PaymentRepository payments, Clock clock) {
        this.payments = payments;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${shop.payment.maintenance-interval:1h}")
    int runOnce() {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(RETENTION);
        int total = drain(() -> payments.deleteWebhookEventsBefore(cutoff, BATCH))
                + drain(() -> payments.deleteUnmatchedBefore(cutoff, BATCH));
        if (total > 0) {
            LOGGER.info("Deleted {} old webhook records", total);
        }
        return total;
    }

    private static int drain(IntSupplier deleteBatch) {
        int total = 0;
        int batch;
        do {
            batch = deleteBatch.getAsInt();
            total += batch;
        } while (batch == BATCH);
        return total;
    }
}
