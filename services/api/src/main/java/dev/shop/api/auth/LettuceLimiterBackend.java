package dev.shop.api.auth;

import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.SslOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.resource.ClientResources;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.ssl.SslBundle;

/**
 * The limiter's own Valkey client, separate from the session store's so that its short timeouts cannot affect sessions
 * and a hung limiter cannot hold session connections. The connection is opened on the first request that needs it,
 * never at startup. Lettuce reconnects an established connection by itself; after a failed connect the backend refuses
 * calls for {@link #BACKOFF} so an unreachable store costs one connect timeout per backoff, not one per request. A
 * timeout on an established connection is not a failed connect: it fails that request only, and the next one tries again.
 */
final class LettuceLimiterBackend implements LimiterBackend, DisposableBean {

    static final Duration COMMAND_TIMEOUT = Duration.ofMillis(500);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(1);
    static final Duration BACKOFF = Duration.ofSeconds(5);

    /** A bucket nobody has touched for this long after it refilled is dropped, so idle accounts cost no memory. */
    private static final Duration KEEP_AFTER_FULL = Duration.ofSeconds(10);

    private final RedisClient client;
    private final Clock clock;
    private StatefulRedisConnection<String, byte[]> connection;
    private ProxyManager<String> proxies;
    private long backoffUntilMillis = Long.MIN_VALUE;

    LettuceLimiterBackend(DataRedisConnectionDetails details, ClientResources resources, Clock clock) {
        this.clock = clock;
        this.client = RedisClient.create(resources, uri(details));
        ClientOptions.Builder options = ClientOptions.builder()
                .timeoutOptions(TimeoutOptions.enabled(COMMAND_TIMEOUT))
                .socketOptions(
                        SocketOptions.builder().connectTimeout(COMMAND_TIMEOUT).build())
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
        SslBundle bundle = details.getSslBundle();
        if (bundle != null) {
            options.sslOptions(sslOptions(bundle));
        }
        client.setOptions(options.build());
    }

    /** Synchronized so that threads queued behind a connect attempt find the backoff it set and fail fast. */
    @Override
    public synchronized ProxyManager<String> proxies() {
        if (backingOff()) {
            throw new RedisException("The rate limit store failed recently; not asking again yet");
        }
        if (proxies == null) {
            connect();
        }
        return proxies;
    }

    @Override
    public synchronized void destroy() {
        if (connection != null) {
            connection.close();
        }
        client.shutdown(Duration.ZERO, Duration.ZERO);
    }

    private void connect() {
        try {
            connection = client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
        } catch (RuntimeException e) {
            backoffUntilMillis = clock.millis() + BACKOFF.toMillis();
            throw e;
        }
        proxies = Bucket4jLettuce.casBasedBuilder(connection)
                .clientClock(timeMeter(clock))
                .requestTimeout(REQUEST_TIMEOUT)
                .expirationAfterWrite(
                        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(KEEP_AFTER_FULL))
                .build();
    }

    private boolean backingOff() {
        return clock.millis() < backoffUntilMillis;
    }

    /** Trust and client certificates come from the same SSL bundle the session store's connection uses. */
    private static SslOptions sslOptions(SslBundle bundle) {
        SslOptions.Builder builder =
                SslOptions.builder().trustManager(bundle.getManagers().getTrustManagerFactory());
        return builder.keyManager(bundle.getManagers().getKeyManagerFactory()).build();
    }

    /** Never log the result: it carries the password. */
    static RedisURI uri(DataRedisConnectionDetails details) {
        DataRedisConnectionDetails.Standalone standalone = details.getStandalone();
        if (standalone == null
                || details.getSentinel() != null
                || details.getCluster() != null
                || details.getMasterReplica() != null) {
            throw new IllegalStateException(
                    "The rate limiter supports only a standalone Valkey; sentinel, cluster and master/replica are not supported");
        }
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(standalone.getHost())
                .withPort(standalone.getPort())
                .withDatabase(standalone.getDatabase())
                .withSsl(details.getSslBundle() != null)
                .withTimeout(COMMAND_TIMEOUT);
        String password = details.getPassword();
        if (password != null && !password.isEmpty()) {
            String username = details.getUsername();
            if (username != null && !username.isEmpty()) {
                builder.withAuthentication(username, password.toCharArray());
            } else {
                builder.withPassword(password.toCharArray());
            }
        }
        return builder.build();
    }

    static TimeMeter timeMeter(Clock clock) {
        return new TimeMeter() {
            @Override
            public long currentTimeNanos() {
                Instant now = clock.instant();
                return Math.addExact(Math.multiplyExact(now.getEpochSecond(), 1_000_000_000L), now.getNano());
            }

            @Override
            public boolean isWallClockBased() {
                return true;
            }
        };
    }
}
