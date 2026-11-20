package dev.shop.api.order;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes idempotency keys whose lifetime is over, in batches so no statement holds many locks. */
@Component
class MaintenanceJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(MaintenanceJob.class);

    private final IdempotencyStore idempotency;
    private final CheckoutProperties properties;
    private final Clock clock;

    MaintenanceJob(IdempotencyStore idempotency, CheckoutProperties properties, Clock clock) {
        this.idempotency = idempotency;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${shop.checkout.maintenance-interval:1h}")
    int runOnce() {
        int total = 0;
        int batch;
        do {
            batch = idempotency.deleteExpired(OffsetDateTime.now(clock), properties.maintenanceBatchSize());
            total += batch;
        } while (batch == properties.maintenanceBatchSize());
        if (total > 0) {
            LOGGER.info("Deleted {} expired idempotency keys", total);
        }
        return total;
    }
}
