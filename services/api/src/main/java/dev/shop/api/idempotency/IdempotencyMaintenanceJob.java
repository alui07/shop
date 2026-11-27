package dev.shop.api.idempotency;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes idempotency keys whose lifetime is over, in batches so no statement holds many locks. */
@Component
class IdempotencyMaintenanceJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(IdempotencyMaintenanceJob.class);

    private final IdempotencyStore store;
    private final IdempotencyProperties properties;
    private final Clock clock;

    IdempotencyMaintenanceJob(IdempotencyStore store, IdempotencyProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${shop.idempotency.maintenance-interval:1h}")
    int runOnce() {
        int total = 0;
        int batch;
        do {
            batch = store.deleteExpired(OffsetDateTime.now(clock), properties.maintenanceBatchSize());
            total += batch;
        } while (batch == properties.maintenanceBatchSize());
        if (total > 0) {
            LOGGER.info("Deleted {} expired idempotency keys", total);
        }
        return total;
    }
}
