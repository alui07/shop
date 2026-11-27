package dev.shop.api.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.ShopIT;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class IdempotencyMaintenanceIT extends ShopIT {

    @Autowired
    private IdempotencyStore store;

    @Test
    void theMaintenanceJobDeletesOnlyExpiredKeysInBatches() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (int i = 0; i < 5; i++) {
            insertKey("old-" + i, now.minusHours(1));
        }
        insertKey("live", now.plusHours(1));
        IdempotencyMaintenanceJob job = new IdempotencyMaintenanceJob(store, new IdempotencyProperties(null, 2), clock);

        assertThat(job.runOnce()).isEqualTo(5);

        assertThat(count()).isEqualTo(1);
        assertThat(job.runOnce()).isZero();
    }

    private void insertKey(String subject, OffsetDateTime expiresAt) {
        jdbc.sql("""
                        INSERT INTO idempotency_key (customer_subject, key, request_hash, created_at, expires_at)
                        VALUES (:subject, :key, 'h', :created, :expires)
                        """)
                .param("subject", subject)
                .param("key", UUID.randomUUID())
                .param("created", expiresAt.minusDays(1))
                .param("expires", expiresAt)
                .update();
    }

    private int count() {
        return jdbc.sql("SELECT count(*) FROM idempotency_key")
                .query(Integer.class)
                .single();
    }
}
