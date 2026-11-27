package dev.shop.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

class CatalogChangedSubscriberTest {

    private static final Duration THREE_SECONDS = Duration.ofSeconds(3);

    private final RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
    private final CatalogChangedSubscriber subscriber = subscriberFor(container, Duration.ofSeconds(2));

    private static CatalogChangedSubscriber subscriberFor(
            RedisMessageListenerContainer container, Duration stopTimeout) {
        CatalogChangedSubscriber subscriber =
                new CatalogChangedSubscriber(container, Duration.ofMillis(1), Duration.ofMillis(4), stopTimeout);
        subscriber.start();
        return subscriber;
    }

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
    void neverStartsTheContainerOnceStopped() {
        subscriber.stop();

        subscriber.subscribeWithBackoff();
        subscriber.onApplicationEvent(mock(ApplicationReadyEvent.class));

        verify(container, never()).start();
        assertThat(subscriber.isRunning()).isFalse();
    }

    @Test
    void stopsRetryingWhenStoppedDuringAnAttempt() {
        doAnswer(invocation -> {
                    subscriber.stop();
                    throw new RedisConnectionFailureException("down");
                })
                .when(container)
                .start();

        subscriber.subscribeWithBackoff();

        verify(container, times(1)).start();
    }

    @Test
    void subscribesOffTheCallingThreadWhenTheApplicationIsReady() {
        doNothing().when(container).start();

        subscriber.onApplicationEvent(mock(ApplicationReadyEvent.class));

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> verify(container).start());
        subscriber.stop();
        verify(container, never()).stop();
        assertThat(subscriber.isRunning()).isFalse();
    }

    @Test
    void stopIsIdempotentAndDestroyIsABackstop() {
        subscriber.stop();
        subscriber.stop();
        subscriber.destroy();

        assertThat(subscriber.isRunning()).isFalse();
    }

    @Test
    void stopReturnsAfterTheStopTimeoutWhenTheAttemptIgnoresTheInterrupt() {
        CountDownLatch attemptEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
                    attemptEntered.countDown();
                    awaitIgnoringInterrupts(release);
                    return null;
                })
                .when(container)
                .start();
        CatalogChangedSubscriber stubborn = subscriberFor(container, Duration.ofMillis(50));
        stubborn.onApplicationEvent(mock(ApplicationReadyEvent.class));
        try {
            awaitLatch(attemptEntered);

            assertTimeoutPreemptively(THREE_SECONDS, () -> {
                stubborn.stop();
            });

            assertThat(stubborn.isRunning()).isFalse();
        } finally {
            release.countDown();
        }
    }

    @Test
    void anInterruptedCallerKeepsItsInterruptFlagWhileStopping() {
        CountDownLatch attemptEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
                    attemptEntered.countDown();
                    awaitIgnoringInterrupts(release);
                    return null;
                })
                .when(container)
                .start();
        CatalogChangedSubscriber busy = subscriberFor(container, Duration.ofSeconds(2));
        busy.onApplicationEvent(mock(ApplicationReadyEvent.class));
        try {
            awaitLatch(attemptEntered);
            Thread.currentThread().interrupt();

            busy.stop();

            assertThat(Thread.interrupted()).isTrue();
        } finally {
            release.countDown();
        }
    }

    @Test
    void aFailedSubscribeReturnsToTheCallerInsteadOfRetryingInsideTheContainer() throws Exception {
        RedisMessageListenerContainer real = refusingContainer(refusingFactory());
        try {
            assertTimeoutPreemptively(
                    THREE_SECONDS, () -> assertThatThrownBy(real::start).isInstanceOf(RuntimeException.class));
        } finally {
            real.stop();
            real.destroy();
        }
    }

    @Test
    void stopsPromptlyWhileValkeyRefusesTheSubscription() throws Exception {
        LettuceConnectionFactory factory = refusingFactory();
        RedisMessageListenerContainer real = refusingContainer(factory);
        CatalogChangedSubscriber refused = subscriberFor(real, Duration.ofSeconds(2));
        try {
            refused.onApplicationEvent(mock(ApplicationReadyEvent.class));
            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> verify(factory, atLeast(3)).getConnection());

            assertTimeoutPreemptively(THREE_SECONDS, () -> {
                refused.stop();
                real.stop();
            });

            assertThat(refused.isRunning()).isFalse();
        } finally {
            refused.stop();
            real.stop();
            real.destroy();
        }
    }

    private static LettuceConnectionFactory refusingFactory() {
        LettuceConnectionFactory factory = mock(LettuceConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.isSubscribed()).thenReturn(false);
        doThrow(new RedisConnectionFailureException("refused")).when(connection).subscribe(any(), any(byte[][].class));
        return factory;
    }

    private static RedisMessageListenerContainer refusingContainer(LettuceConnectionFactory factory) {
        RedisMessageListenerContainer real = new CatalogCacheConfiguration()
                .catalogChangedListenerContainer(factory, mock(CatalogCacheInvalidation.class));
        real.afterPropertiesSet();
        return real;
    }

    private static void awaitLatch(CountDownLatch latch) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> latch.getCount() == 0);
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        while (latch.getCount() > 0) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
