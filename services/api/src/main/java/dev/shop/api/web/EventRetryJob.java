package dev.shop.api.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resubmits failed event publications for every module. One replica at a time (a session-level advisory lock on its own
 * connection, so the resubmission itself runs outside any transaction). Publications that kept failing for about a day
 * are parked in {@code event_publication_dead_letter} first, so they never crowd out newer ones.
 */
@Component
public class EventRetryJob {

    static final long LOCK_KEY = 7_340_011L;
    static final int MAX_RESUBMITTED_PER_RUN = 200;
    static final Duration MAX_BACKOFF = Duration.ofMinutes(30);

    private static final Logger LOGGER = LoggerFactory.getLogger(EventRetryJob.class);

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbc;
    private final FailedEventPublications failedPublications;
    private final Clock clock;
    private final int maxAttempts;

    EventRetryJob(
            JdbcTemplate jdbcTemplate,
            JdbcClient jdbc,
            FailedEventPublications failedPublications,
            Clock clock,
            @Value("${shop.events.max-attempts:48}") int maxAttempts) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbc = jdbc;
        this.failedPublications = failedPublications;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${shop.events.retry-interval:60s}")
    public void runOnce() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            JdbcTemplate lockSession = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            if (Boolean.TRUE.equals(
                    lockSession.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK_KEY))) {
                try {
                    parkDeadAndResubmit();
                } finally {
                    lockSession.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, LOCK_KEY);
                }
            }
            return null;
        });
    }

    private void parkDeadAndResubmit() {
        Instant now = clock.instant();
        Map<String, Long> parkedByListener = parkDead(now);
        long parked =
                parkedByListener.values().stream().mapToLong(Long::longValue).sum();
        parkedByListener.forEach((listener, count) -> LOGGER.warn(
                "Event retry: {} publications for listener {} gave up and moved to the dead letter table",
                count,
                listener));
        long failed = jdbc.sql("SELECT count(*) FROM event_publication WHERE status = 'FAILED'")
                .query(Long.class)
                .single();
        AtomicInteger admitted = new AtomicInteger();
        failedPublications.resubmit(ResubmissionOptions.defaults()
                .withBatchSize((int) Math.max(1, failed))
                .withFilter(publication ->
                        backoffElapsed(publication, now) && admitted.getAndIncrement() < MAX_RESUBMITTED_PER_RUN));
        if (parked > 0 || failed > 0) {
            LOGGER.info("Event retry: {} failed publications, {} moved to the dead letter table", failed, parked);
        }
    }

    private Map<String, Long> parkDead(Instant now) {
        return jdbc
                .sql("""
                        WITH dead AS (
                            DELETE FROM event_publication WHERE status = 'FAILED' AND completion_attempts >= :max
                            RETURNING *),
                            moved AS (
                            INSERT INTO event_publication_dead_letter (id, listener_id, event_type, serialized_event,
                                publication_date, completion_date, status, completion_attempts, last_resubmission_date, moved_at)
                            SELECT id, listener_id, event_type, serialized_event, publication_date, completion_date, status,
                                   completion_attempts, last_resubmission_date, :now
                            FROM dead
                            RETURNING listener_id)
                        SELECT listener_id, count(*) AS parked FROM moved GROUP BY listener_id
                        """)
                .param("max", maxAttempts)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .query((row, rowNumber) -> Map.entry(row.getString("listener_id"), row.getLong("parked")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** 1, 1, 2, 4, 8, 16 minutes, then 30 (attempts count from 0), measured from the last attempt. */
    private static boolean backoffElapsed(EventPublication publication, Instant now) {
        int doublings = Math.min(Math.max(publication.getCompletionAttempts() - 1, 0), 5);
        Duration backoff = Duration.ofMinutes(1L << doublings);
        Duration capped = backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
        return !publication.getLastPublicationDate().plus(capped).isAfter(now);
    }
}
