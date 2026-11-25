package dev.shop.api.auth;

import io.lettuce.core.resource.ClientResources;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A second Valkey that only the limiter uses, so a test can pause it without touching sessions. It is not a service
 * connection, so the session store keeps using the shared container.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RateLimitTestConfiguration {

    private static final int VALKEY_PORT = 6379;

    @Bean
    GenericContainer<?> limiterValkey() {
        String image = System.getenv("VALKEY_IMAGE");
        if (image == null || image.isBlank()) {
            throw new IllegalStateException("Environment variable VALKEY_IMAGE must name the Valkey image");
        }
        return new GenericContainer<>(DockerImageName.parse(image)).withExposedPorts(VALKEY_PORT);
    }

    @Bean(destroyMethod = "destroy")
    @Primary
    LimiterBackend limiterBackendOnTheLimiterValkey(
            @Qualifier("limiterValkey") GenericContainer<?> limiterValkey, ClientResources resources, Clock clock) {
        return new LettuceLimiterBackend(detailsOf(limiterValkey), resources, clock);
    }

    static DataRedisConnectionDetails detailsOf(GenericContainer<?> valkey) {
        return new DataRedisConnectionDetails() {
            @Override
            public Standalone getStandalone() {
                return Standalone.of(valkey.getHost(), valkey.getMappedPort(VALKEY_PORT));
            }
        };
    }
}
