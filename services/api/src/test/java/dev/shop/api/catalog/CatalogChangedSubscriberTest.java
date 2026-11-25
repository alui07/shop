package dev.shop.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

class CatalogChangedSubscriberTest {

    private final RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
    private final CatalogChangedSubscriber subscriber =
            new CatalogChangedSubscriber(container, Duration.ofMillis(1), Duration.ofMillis(4));

    @Test
    void retriesUntilTheSubscriptionIsEstablished() {
        doThrow(new RedisConnectionFailureException("down"))
                .doThrow(new RedisConnectionFailureException("down"))
                .doThrow(new RedisConnectionFailureException("down"))
                .doThrow(new RedisConnectionFailureException("down"))
                .doNothing()
                .when(container)
                .start();

        subscriber.subscribeWithBackoff();

        verify(container, times(5)).start();
        verify(container, times(4)).stop();
    }

    @Test
    void startsOnceWhenValkeyIsUp() {
        subscriber.subscribeWithBackoff();

        verify(container, times(1)).start();
        verify(container, never()).stop();
    }

    @Test
    void stopsRetryingWhenInterrupted() {
        doThrow(new RedisConnectionFailureException("down")).when(container).start();
        Thread.currentThread().interrupt();

        subscriber.subscribeWithBackoff();

        assertThat(Thread.interrupted()).isTrue();
        verify(container, times(1)).start();
    }

    @Test
    void subscribesOffTheCallingThreadWhenTheApplicationIsReady() {
        doNothing().when(container).start();

        subscriber.onApplicationEvent(mock(ApplicationReadyEvent.class));

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> verify(container).start());
        subscriber.destroy();
        verify(container, never()).stop();
    }
}
