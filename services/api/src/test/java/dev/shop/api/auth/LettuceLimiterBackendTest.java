package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.shop.api.support.MutableClock;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisException;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslStoreBundle;

class LettuceLimiterBackendTest {

    private final ClientResources resources = DefaultClientResources.create();
    private final MutableClock clock = new MutableClock();

    @AfterEach
    void shutDown() {
        resources.shutdown(0, 0, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static DataRedisConnectionDetails unreachable(String username, String password) {
        return new DataRedisConnectionDetails() {
            @Override
            public Standalone getStandalone() {
                return Standalone.of("localhost", 1);
            }

            @Override
            public String getUsername() {
                return username;
            }

            @Override
            public String getPassword() {
                return password;
            }
        };
    }

    @Test
    void aFailedConnectIsRefusedFastUntilTheBackoffEnds() {
        LettuceLimiterBackend backend = new LettuceLimiterBackend(unreachable(null, null), resources, clock);
        try {
            assertThatThrownBy(backend::proxies).isInstanceOf(RedisConnectionException.class);

            assertThatThrownBy(backend::proxies)
                    .isInstanceOf(RedisException.class)
                    .isNotInstanceOf(RedisConnectionException.class)
                    .hasMessageContaining("not asking again yet");

            clock.advance(LettuceLimiterBackend.BACKOFF);
            assertThatThrownBy(backend::proxies).isInstanceOf(RedisConnectionException.class);
        } finally {
            backend.destroy();
        }
    }

    @Test
    void credentialsAreCarriedInTheShapeTheDetailsHave() {
        RedisCredentials both = credentialsOf(unreachable("limiter", "secret"));
        assertThat(both.hasUsername()).isTrue();
        assertThat(both.getUsername()).isEqualTo("limiter");
        assertThat(both.hasPassword()).isTrue();

        RedisCredentials passwordOnly = credentialsOf(unreachable(null, "secret"));
        assertThat(passwordOnly.hasUsername()).isFalse();
        assertThat(passwordOnly.hasPassword()).isTrue();

        RedisCredentials blankUser = credentialsOf(unreachable("", "secret"));
        assertThat(blankUser.hasUsername()).isFalse();
        assertThat(blankUser.hasPassword()).isTrue();

        RedisCredentials noPassword = credentialsOf(unreachable("limiter", ""));
        assertThat(noPassword.hasUsername()).isFalse();
        assertThat(noPassword.hasPassword()).isFalse();

        assertThat(credentialsOf(unreachable(null, null)).hasPassword()).isFalse();
    }

    private static RedisCredentials credentialsOf(DataRedisConnectionDetails details) {
        return LettuceLimiterBackend.uri(details)
                .getCredentialsProvider()
                .resolveCredentials()
                .block();
    }

    @Test
    void sslIsOnOnlyWhenTheConnectionDetailsCarryASslBundle() {
        assertThat(LettuceLimiterBackend.uri(unreachable(null, null)).isSsl()).isFalse();

        DataRedisConnectionDetails tls = withSslBundle(SslBundle.of(SslStoreBundle.NONE));
        assertThat(LettuceLimiterBackend.uri(tls).isSsl()).isTrue();

        LettuceLimiterBackend backend = new LettuceLimiterBackend(tls, resources, clock);
        backend.destroy();
    }

    @Test
    void aTopologyOtherThanStandaloneFailsAtStartUp() {
        assertThatThrownBy(() -> new LettuceLimiterBackend(
                        topology(mock(DataRedisConnectionDetails.Sentinel.class), null, null), resources, clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("standalone");
        assertThatThrownBy(() ->
                        LettuceLimiterBackend.uri(topology(null, mock(DataRedisConnectionDetails.Cluster.class), null)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> LettuceLimiterBackend.uri(
                        topology(null, null, mock(DataRedisConnectionDetails.MasterReplica.class))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> LettuceLimiterBackend.uri(topology(null, null, null, false)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static DataRedisConnectionDetails withSslBundle(SslBundle bundle) {
        return new DataRedisConnectionDetails() {
            @Override
            public Standalone getStandalone() {
                return Standalone.of("localhost", 1);
            }

            @Override
            public SslBundle getSslBundle() {
                return bundle;
            }
        };
    }

    private static DataRedisConnectionDetails topology(
            DataRedisConnectionDetails.Sentinel sentinel,
            DataRedisConnectionDetails.Cluster cluster,
            DataRedisConnectionDetails.MasterReplica masterReplica) {
        return topology(sentinel, cluster, masterReplica, true);
    }

    private static DataRedisConnectionDetails topology(
            DataRedisConnectionDetails.Sentinel sentinel,
            DataRedisConnectionDetails.Cluster cluster,
            DataRedisConnectionDetails.MasterReplica masterReplica,
            boolean standalone) {
        return new DataRedisConnectionDetails() {
            @Override
            public Standalone getStandalone() {
                return standalone ? Standalone.of("localhost", 1) : null;
            }

            @Override
            public Sentinel getSentinel() {
                return sentinel;
            }

            @Override
            public Cluster getCluster() {
                return cluster;
            }

            @Override
            public MasterReplica getMasterReplica() {
                return masterReplica;
            }
        };
    }

    @Test
    void theTimeMeterReadsTheApplicationClockAsWallClockNanoseconds() {
        io.github.bucket4j.TimeMeter meter = LettuceLimiterBackend.timeMeter(clock);
        long before = meter.currentTimeNanos();
        clock.advance(Duration.ofSeconds(10));

        assertThat(meter.currentTimeNanos() - before).isBetween(10_000_000_000L, 10_500_000_000L);
        assertThat(meter.isWallClockBased()).isTrue();
    }
}
