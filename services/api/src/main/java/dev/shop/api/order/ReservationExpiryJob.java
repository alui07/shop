package dev.shop.api.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final Counter releasedUnits;

    ReservationExpiryJob(OrderExpiry expiry, CheckoutProperties properties, Clock clock, MeterRegistry meters) {
        this.expiry = expiry;
        this.properties = properties;
        this.clock = clock;
        this.releasedUnits = Counter.builder("shop.reservations.released.units")
                .description("Stock units given back by the reservation expiry job")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${shop.checkout.expiry-interval:30s}")
    int runOnce() {
        int total = 0;
        int batchOrders;
        do {
            OrderExpiry.ExpiredBatch batch =
                    expiry.expireBatch(OffsetDateTime.now(clock), properties.expiryBatchSize());
            batchOrders = batch.orders();
            total += batchOrders;
            releasedUnits.increment(batch.units());
        } while (batchOrders == properties.expiryBatchSize());
        if (total > 0) {
            LOGGER.info("Expired {} unpaid orders", total);
        }
        return total;
    }
}
