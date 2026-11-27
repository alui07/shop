package dev.shop.api.idempotency;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The {@code order_id} column holds the id of the resource the first request created; it is null for cart additions. */
@Repository
class IdempotencyStore {

    record StoredKey(String requestHash, UUID resourceId) {}

    private final JdbcClient jdbc;

    IdempotencyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Takes the key, or renews it when its lifetime is over; false means a live key exists. A second request with the
     * same key waits here on the first one's uncommitted row until that commits or rolls back.
     */
    boolean claim(String subject, UUID key, String requestHash, OffsetDateTime now, OffsetDateTime expiresAt) {
        return jdbc.sql("""
                        INSERT INTO idempotency_key (customer_subject, key, request_hash, created_at, expires_at)
                        VALUES (:subject, :key, :hash, :now, :expiresAt)
                        ON CONFLICT (customer_subject, key) DO UPDATE
                        SET request_hash = EXCLUDED.request_hash, created_at = EXCLUDED.created_at,
                            expires_at = EXCLUDED.expires_at, response_status = NULL, order_id = NULL
                        WHERE idempotency_key.expires_at <= :now
                        RETURNING key
                        """)
                .param("subject", subject)
                .param("key", key)
                .param("hash", requestHash)
                .param("now", now)
                .param("expiresAt", expiresAt)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    Optional<StoredKey> find(String subject, UUID key) {
        return jdbc.sql(
                        "SELECT request_hash, order_id FROM idempotency_key WHERE customer_subject = :subject AND key = :key")
                .param("subject", subject)
                .param("key", key)
                .query((rs, rowNum) ->
                        new StoredKey(rs.getString("request_hash"), rs.getObject("order_id", UUID.class)))
                .optional();
    }

    void complete(String subject, UUID key, int responseStatus, UUID resourceId) {
        jdbc.sql("""
                        UPDATE idempotency_key SET response_status = :status, order_id = :resource
                        WHERE customer_subject = :subject AND key = :key
                        """)
                .param("status", responseStatus)
                .param("resource", resourceId)
                .param("subject", subject)
                .param("key", key)
                .update();
    }

    @Transactional
    int deleteExpired(OffsetDateTime now, int limit) {
        return jdbc.sql("""
                        DELETE FROM idempotency_key WHERE ctid IN (
                            SELECT ctid FROM idempotency_key WHERE expires_at <= :now LIMIT :limit FOR UPDATE SKIP LOCKED)
                        """).param("now", now).param("limit", limit).update();
    }
}
