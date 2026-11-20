package dev.shop.api.order;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Gives back the stock of unpaid orders whose hold has run out; safe to run on several replicas at once. */
@Component
class ReservationExpiryJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReservationExpiryJob.class);

    private final OrderExpiry expiry;
    private final CheckoutProperties properties;
    private final Clock clock;

    ReservationExpiryJob(OrderExpiry expiry, CheckoutProperties properties, Clock clock) {
        this.expiry = expiry;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${shop.checkout.expiry-interval:30s}")
    int runOnce() {
        int total = 0;
        int batch;
        do {
            batch = expiry.expireBatch(OffsetDateTime.now(clock), properties.expiryBatchSize());
            total += batch;
        } while (batch == properties.expiryBatchSize());
        if (total > 0) {
            LOGGER.info("Expired {} unpaid orders", total);
        }
        return total;
    }
}
