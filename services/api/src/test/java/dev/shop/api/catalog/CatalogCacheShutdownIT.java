package dev.shop.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;

import dev.shop.api.support.ShopIT;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Closing a pod must finish promptly when Valkey refuses the pub/sub connection while the shared connection already
 * exists. The listener container's own recovery used to sleep and recurse while holding its lock, so {@code close()}
 * deadlocked (spring-data-redis #2822).
 */
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(
        properties = {"spring.cache.type=caffeine", "spring.cache.caffeine.spec=maximumSize=2000,expireAfterWrite=15s"})
class CatalogCacheShutdownIT extends ShopIT {

    @Autowired
    private PostgreSQLContainer postgres;

    @Autowired
    private GenericContainer<?> valkey;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void aPodClosesPromptlyWhenValkeyRefusesItsSubscriptionAfterTheSharedConnectionExists(CapturedOutput output) {
        ConfigurableApplicationContext other =
                SecondApiContext.start(postgres, valkey, "maximumSize=2000,expireAfterWrite=15s");
        int offset;
        try {
            Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .until(() -> CatalogChannelSubscribers.count(redisTemplate) >= 2);
            other.getBean("catalogChangedListenerContainer", RedisMessageListenerContainer.class)
                    .stop();
            valkey.stop();
            offset = output.getAll().length();

            other.getBean(CatalogChangedSubscriber.class).onApplicationEvent(mock(ApplicationReadyEvent.class));

            Awaitility.await()
                    .atMost(Duration.ofSeconds(15))
                    .until(() -> output.getAll()
                            .substring(offset)
                            .matches("(?s).*(SubscriptionTask aborted with exception|Restarting subscription task).*"));
        } catch (RuntimeException | Error failure) {
            other.close();
            throw failure;
        }

        assertTimeoutPreemptively(Duration.ofSeconds(10), other::close);

        assertThat(output.getAll().substring(offset)).doesNotContain("Restarting subscription task");
    }
}
