package dev.shop.api.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claims the right to send one email, in its own short transaction, so no row lock or connection is held while the
 * mail server is slow. A claim is only taken from a failed attempt or from one old enough to be a crash.
 */
@Component
class OrderEmails {

    enum Claim {
        CLAIMED,
        ALREADY_SENT,
        IN_PROGRESS
    }

    static final String CONFIRMATION = "CONFIRMATION";
    static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(1);

    private final JdbcClient jdbc;
    private final Clock clock;

    OrderEmails(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    Claim claim(UUID orderId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        int claimed = jdbc.sql("""
                        INSERT INTO order_email (order_id, kind, status, claimed_at)
                        VALUES (:order, :kind, 'SENDING', :now)
                        ON CONFLICT (order_id, kind) DO UPDATE SET status = 'SENDING', claimed_at = :now
                        WHERE order_email.status = 'FAILED'
                           OR (order_email.status = 'SENDING' AND order_email.claimed_at < :stale)
                        """)
                .param("order", orderId)
                .param("kind", CONFIRMATION)
                .param("now", now)
                .param("stale", now.minus(CLAIM_TIMEOUT))
                .update();
        if (claimed == 1) {
            return Claim.CLAIMED;
        }
        String status = jdbc.sql("SELECT status FROM order_email WHERE order_id = :order AND kind = :kind")
                .param("order", orderId)
                .param("kind", CONFIRMATION)
                .query(String.class)
                .single();
        return "SENT".equals(status) ? Claim.ALREADY_SENT : Claim.IN_PROGRESS;
    }

    @Transactional
    void markSent(UUID orderId) {
        jdbc.sql("UPDATE order_email SET status = 'SENT', sent_at = :now WHERE order_id = :order AND kind = :kind")
                .param("order", orderId)
                .param("kind", CONFIRMATION)
                .param("now", OffsetDateTime.now(clock))
                .update();
    }

    /** Lets the next attempt claim again at once instead of waiting for the claim to age out. */
    @Transactional
    void release(UUID orderId) {
        jdbc.sql("UPDATE order_email SET status = 'FAILED', claimed_at = NULL WHERE order_id = :order AND kind = :kind")
                .param("order", orderId)
                .param("kind", CONFIRMATION)
                .update();
    }
}
