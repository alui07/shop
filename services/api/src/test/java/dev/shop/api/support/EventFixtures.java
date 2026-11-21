package dev.shop.api.support;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class EventFixtures {

    private EventFixtures() {}

    /** Waits until every event publication has completed (completed ones are deleted). */
    public static void awaitQuiet(JdbcClient jdbc) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> jdbc.sql("SELECT count(*) FROM event_publication")
                                .query(Integer.class)
                                .single()
                        == 0);
    }
}
