package dev.shop.api.order;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AdminClient;
import dev.shop.api.support.ShopIT;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class ReservationExpiryIT extends ShopIT {

    @Autowired
    private OrderExpiry expiry;

    @Autowired
    private ReservationExpiryJob job;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MeterRegistry meters;

    @Test
    void anOverdueOrderIsExpiredAndItsStockGivenBackWhileALaterOneStays() {
        AdminClient client = customer("expiry-basic");
        JsonNode overdue = placeOrderFor(client, LAMP, 3);
        clock.advance(Duration.ofMinutes(10));
        JsonNode stillHeld = placeOrderFor(client, LAMP, 2);
        clock.advance(Duration.ofMinutes(6));

        assertThat(job.runOnce()).isEqualTo(1);

        assertThat(orderStatus(overdue.get("id").asString())).isEqualTo("EXPIRED");
        assertThat(orderStatus(stillHeld.get("id").asString())).isEqualTo("PENDING_PAYMENT");
        assertThat(reserved(lampId)).isEqualTo(2);
        assertStockInvariant();
        assertThat(job.runOnce()).isZero();
        assertThat(ok(client.get("/api/orders/" + overdue.get("id").asString()))
                        .get("status")
                        .asString())
                .isEqualTo("EXPIRED");
    }

    @Test
    void theReleasedUnitsCounterAddsUpTheStockGivenBack() {
        AdminClient client = customer("expiry-units");
        placeOrderFor(client, LAMP, 3);
        clock.advance(Duration.ofMinutes(16));
        double before = releasedUnits();

        job.runOnce();

        assertThat(releasedUnits() - before).isEqualTo(3.0);
        job.runOnce();
        assertThat(releasedUnits() - before).isEqualTo(3.0);
    }

    private double releasedUnits() {
        return meters.get("shop.reservations.released.units").counter().count();
    }

    @Test
    void twoJobsRunningTogetherReleaseEveryOrderExactlyOnce() throws Exception {
        jdbc.sql("UPDATE stock SET on_hand = 1000").update();
        for (int i = 0; i < 250; i++) {
            insertPendingOrder(
                    "expiry-bulk", clock.instant().minusSeconds(60), new long[] {lampId, 1}, new long[] {notebookId, 2
                    });
        }
        ReservationExpiryJob other = new ReservationExpiryJob(expiry, properties(50), clock, new SimpleMeterRegistry());

        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(job::runOnce);
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(other::runOnce);

        int raced = first.get() + second.get();
        int nextRun = job.runOnce(); // rows the other job held locked are skipped and wait for the next run
        assertThat(raced + nextRun).isEqualTo(250);
        assertThat(jdbc.sql("SELECT count(*) FROM orders WHERE status = 'EXPIRED'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(250);
        assertThat(reserved(lampId)).isZero();
        assertThat(reserved(notebookId)).isZero();
        assertStockInvariant();
    }

    @Test
    void anOrderLockedByAnotherTransactionIsSkippedAndReleasedOnTheNextRun() throws Exception {
        UUID locked = insertPendingOrder("expiry-locked", clock.instant().minusSeconds(60), new long[] {lampId, 1});
        insertPendingOrder("expiry-free", clock.instant().minusSeconds(60), new long[] {lampId, 1});
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement("SELECT 1 FROM orders WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, locked);
                lock.execute();
            }

            assertThat(job.runOnce()).isEqualTo(1);
            assertThat(orderStatus(locked)).isEqualTo("PENDING_PAYMENT");
            assertThat(reserved(lampId)).isEqualTo(1);
            other.rollback();
        }

        assertThat(job.runOnce()).isEqualTo(1);
        assertThat(orderStatus(locked)).isEqualTo("EXPIRED");
        assertThat(reserved(lampId)).isZero();
        assertStockInvariant();
    }

    @Test
    void theJobKeepsTakingBatchesWhileTheyComeBackFull() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(insertPendingOrder("expiry-batches", clock.instant().minusSeconds(60), new long[] {lampId, 1}));
        }
        ReservationExpiryJob small = new ReservationExpiryJob(expiry, properties(2), clock, new SimpleMeterRegistry());

        assertThat(small.runOnce()).isEqualTo(5);

        assertThat(ids).allSatisfy(id -> assertThat(orderStatus(id)).isEqualTo("EXPIRED"));
        assertThat(reserved(lampId)).isZero();
    }

    private static CheckoutProperties properties(int expiryBatchSize) {
        return new CheckoutProperties(
                Duration.ofMinutes(15), 3, 10, Duration.ofHours(24), expiryBatchSize, 1000, Duration.ofMinutes(30));
    }
}
