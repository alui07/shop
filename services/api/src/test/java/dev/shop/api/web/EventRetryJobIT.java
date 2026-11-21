package dev.shop.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.shop.api.support.EventFixtures;
import dev.shop.api.support.PaymentShopIT;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.modulith.events.EventPublication.Status;
import org.springframework.modulith.events.config.StalenessProperties;
import org.springframework.modulith.events.core.EventPublicationRegistry;

/** The retry job over publications written straight into the registry table, the way failures and crashes leave them. */
@ExtendWith(OutputCaptureExtension.class)
class EventRetryJobIT extends PaymentShopIT {

    private static final String LISTENER = "notification.order-confirmation";

    @Autowired
    private EventRetryJob job;

    @Autowired
    private EventPublicationRegistry registry;

    @Autowired
    private StalenessProperties staleness;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void leaveNothingBehind() {
        jdbc.sql("DELETE FROM event_publication").update();
        jdbc.sql("DELETE FROM event_publication_dead_letter").update();
    }

    @Test
    void aPublicationIsResubmittedOnlyOnceItsBackoffHasElapsed() {
        String dueEmail = mailAddress("due");
        String freshEmail = mailAddress("fresh");
        String cappedInsideEmail = mailAddress("capped-inside");
        String cappedDueEmail = mailAddress("capped-due");
        insertFailed(orderWithEmail(dueEmail), 1, clock.instant().minusSeconds(300));
        insertFailed(orderWithEmail(freshEmail), 1, clock.instant().minusSeconds(30));
        insertFailed(orderWithEmail(cappedInsideEmail), 10, clock.instant().minus(Duration.ofMinutes(29)));
        insertFailed(orderWithEmail(cappedDueEmail), 10, clock.instant().minus(Duration.ofMinutes(31)));

        job.runOnce();

        await().atMost(Duration.ofSeconds(30))
                .until(() -> mailpit.messagesTo(dueEmail).size() == 1
                        && mailpit.messagesTo(cappedDueEmail).size() == 1);
        assertThat(mailpit.messagesTo(freshEmail)).isEmpty();
        assertThat(mailpit.messagesTo(cappedInsideEmail)).isEmpty();
        assertThat(failedCount()).isEqualTo(2);
    }

    @Test
    void aFirstAttemptThatNeverCountedStillWaitsAMinute() {
        String email = mailAddress("zero");
        insertFailed(orderWithEmail(email), 0, clock.instant().minusSeconds(30));

        job.runOnce();

        assertThat(failedCount()).isEqualTo(1);
        assertThat(mailpit.messagesTo(email)).isEmpty();
    }

    @Test
    void publicationsThatFailedForADayAreParkedInTheDeadLetterTable(CapturedOutput output) {
        insertManyFailed(3, 48, Duration.ofHours(1));
        insertFailed(UUID.randomUUID(), 49, clock.instant().minus(Duration.ofHours(2)));

        job.runOnce();

        assertThat(failedCount()).isZero();
        assertThat(count("event_publication_dead_letter")).isEqualTo(4);
        assertThat(count("event_publication_dead_letter WHERE moved_at IS NOT NULL AND listener_id = '" + LISTENER
                        + "'"))
                .isEqualTo(4);
        assertThat(output.getAll())
                .contains("4 publications for listener " + LISTENER + " gave up and moved to the dead letter table");
    }

    @Test
    void aNewerPublicationIsStillResubmittedWhileManyAreDeadLettered() {
        insertManyFailed(250, 48, Duration.ofHours(1));
        String email = mailAddress("newer");
        insertFailed(orderWithEmail(email), 1, clock.instant().minusSeconds(300));

        job.runOnce();

        await().atMost(Duration.ofSeconds(30))
                .until(() -> mailpit.messagesTo(email).size() == 1);
        assertThat(count("event_publication_dead_letter")).isEqualTo(250);
    }

    @Test
    void manyPublicationsInsideTheirBackoffDoNotStarveOneThatIsDue() {
        insertManyFailed(250, 6, Duration.ofMinutes(20));
        String email = mailAddress("starved");
        insertFailed(orderWithEmail(email), 1, clock.instant().minusSeconds(300));

        job.runOnce();

        await().atMost(Duration.ofSeconds(30))
                .until(() -> mailpit.messagesTo(email).size() == 1);
        assertThat(failedCount()).isEqualTo(250);
    }

    @Test
    void atMostTwoHundredDuePublicationsAreResubmittedPerRun() {
        insertManyFailed(250, 1, Duration.ofMinutes(5));

        job.runOnce();

        await().atMost(Duration.ofSeconds(60)).until(() -> failedCount() == 50);
    }

    @Test
    void anotherReplicaHoldingTheLockMakesThisRunDoNothing() throws Exception {
        String email = mailAddress("locked");
        insertFailed(orderWithEmail(email), 1, clock.instant().minusSeconds(300));

        try (Connection other = dataSource.getConnection()) {
            other.createStatement().execute("SELECT pg_advisory_lock(" + EventRetryJob.LOCK_KEY + ")");

            job.runOnce();

            assertThat(failedCount()).isEqualTo(1);
            other.createStatement().execute("SELECT pg_advisory_unlock(" + EventRetryJob.LOCK_KEY + ")");
        }

        job.runOnce();

        await().atMost(Duration.ofSeconds(30))
                .until(() -> mailpit.messagesTo(email).size() == 1);
    }

    @Test
    void aRunWithNothingFailedDoesNothing() {
        job.runOnce();

        assertThat(count("event_publication")).isZero();
    }

    @Test
    void aPublicationLeftPublishedByACrashIsMarkedFailedWhenStaleAndThenResubmittedOnce() {
        String email = mailAddress("crashed");
        UUID order = orderWithEmail(email);
        insertPublication(order, "PUBLISHED", 0, clock.instant());
        clock.advance(Duration.ofMinutes(11));

        registry.markStalePublicationsFailed(staleness);
        assertThat(failedCount()).isEqualTo(1);
        job.runOnce();

        await().atMost(Duration.ofSeconds(30))
                .until(() -> mailpit.messagesTo(email).size() == 1);
        EventFixtures.awaitQuiet(jdbc);
        assertThat(mailpit.messagesTo(email)).hasSize(1);
    }

    @Test
    void theBoundStalenessWatchesPublishedProcessingAndResubmittedPublications() {
        assertThat(staleness.getStaleness(Status.PUBLISHED)).isEqualTo(Duration.ofMinutes(10));
        assertThat(staleness.getStaleness(Status.PROCESSING)).isEqualTo(Duration.ofMinutes(5));
        assertThat(staleness.getStaleness(Status.RESUBMITTED)).isEqualTo(Duration.ofMinutes(5));
        assertThat(staleness.isMonitored(Status.PUBLISHED)).isTrue();
        assertThat(staleness.isMonitored(Status.PROCESSING)).isTrue();
        assertThat(staleness.isMonitored(Status.RESUBMITTED)).isTrue();
    }

    private String mailAddress(String name) {
        return name + "-" + UUID.randomUUID() + "@example.test";
    }

    private UUID orderWithEmail(String email) {
        UUID order = insertPendingOrder(
                "retry-" + UUID.randomUUID(), clock.instant().plusSeconds(900), new long[] {lampId, 1});
        jdbc.sql("UPDATE orders SET email = :email WHERE id = :id")
                .param("email", email)
                .param("id", order)
                .update();
        return order;
    }

    private void insertFailed(UUID order, int attempts, Instant lastAttempt) {
        insertPublication(order, "FAILED", attempts, lastAttempt);
    }

    private void insertPublication(UUID order, String status, int attempts, Instant publishedAt) {
        jdbc.sql("""
                        INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date,
                                                       status, completion_attempts)
                        VALUES (:id, :listener, 'dev.shop.api.order.OrderPaid', :event, :date, :status, :attempts)
                        """)
                .param("id", UUID.randomUUID())
                .param("listener", LISTENER)
                .param("event", "{\"eventId\":\"" + UUID.randomUUID() + "\",\"orderId\":\"" + order + "\"}")
                .param("date", publishedAt.atOffset(ZoneOffset.UTC))
                .param("status", status)
                .param("attempts", attempts)
                .update();
    }

    /** Rows for orders that do not exist: if one is ever resubmitted its listener finds no address and completes. */
    private void insertManyFailed(int rows, int attempts, Duration age) {
        jdbc.sql("""
                        INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date,
                                                       status, completion_attempts)
                        SELECT gen_random_uuid(), :listener, 'dev.shop.api.order.OrderPaid',
                               '{"eventId":"' || gen_random_uuid() || '","orderId":"' || gen_random_uuid() || '"}',
                               :date, 'FAILED', :attempts
                        FROM generate_series(1, :rows)
                        """)
                .param("listener", LISTENER)
                .param("date", clock.instant().minus(age).atOffset(ZoneOffset.UTC))
                .param("attempts", attempts)
                .param("rows", rows)
                .update();
    }

    private int failedCount() {
        return count("event_publication WHERE status = 'FAILED'");
    }

    private int count(String tableAndWhere) {
        return jdbc.sql("SELECT count(*) FROM " + tableAndWhere)
                .query(Integer.class)
                .single();
    }
}
