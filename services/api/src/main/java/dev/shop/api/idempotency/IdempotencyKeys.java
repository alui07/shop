package dev.shop.api.idempotency;

import dev.shop.api.idempotency.IdempotencyStore.StoredKey;
import dev.shop.api.web.LockFailures;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lets an operation run once per key and customer. Both methods join the caller's transaction, so a request that
 * fails rolls its key back and a retry is evaluated again. Not guarded by a role: only guarded services call it.
 */
@Service
public class IdempotencyKeys {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotency-Replayed";

    /** An earlier request with the same key and hash completed; the resource id is null when it made none. */
    public record Completed(UUID resourceId) {}

    private final IdempotencyStore store;
    private final IdempotencyProperties properties;
    private final Clock clock;

    IdempotencyKeys(IdempotencyStore store, IdempotencyProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Empty means the key is new (or expired and renewed): go ahead. A second request with the same key waits on the
     * first one's uncommitted row; if that outlasts lock_timeout, the first is still in progress.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Completed> claim(String subject, UUID key, String requestHash) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        boolean claimed;
        try {
            claimed = store.claim(
                    subject,
                    key,
                    requestHash,
                    now.atOffset(ZoneOffset.UTC),
                    now.plus(properties.ttl()).atOffset(ZoneOffset.UTC));
        } catch (DataAccessException failure) {
            if (LockFailures.isLockTimeout(failure)) {
                throw IdempotencyProblem.inProgress();
            }
            throw failure;
        }
        if (claimed) {
            return Optional.empty();
        }
        StoredKey stored = store.find(subject, key).orElseThrow();
        if (!stored.requestHash().equals(requestHash)) {
            throw IdempotencyProblem.keyReused();
        }
        return Optional.of(new Completed(stored.resourceId()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(String subject, UUID key, int responseStatus, UUID resourceId) {
        store.complete(subject, key, responseStatus, resourceId);
    }

    /** SHA-256 hex over the operation and the parts, joined by newlines. The operation keeps keys from crossing over. */
    public static String requestHash(String operation, Object... parts) {
        StringBuilder canonical = new StringBuilder(operation);
        for (Object part : parts) {
            canonical.append('\n').append(part);
        }
        return digest(canonical.toString(), "SHA-256");
    }

    static String digest(String text, String algorithm) {
        try {
            byte[] digest = MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException unknown) {
            throw new IllegalArgumentException("Unknown digest algorithm", unknown);
        }
    }
}
