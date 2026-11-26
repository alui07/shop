package dev.shop.api.catalog;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Starts the catalog-change subscription once the application is ready, off the startup thread, and retries with
 * backoff until Valkey answers. Valkey is therefore never a startup dependency: until the subscription is up, this
 * pod serves from its own caches and the cache expiry bounds how stale they get. After the first success Lettuce
 * itself re-subscribes when the connection drops; the container has no recovery attempts of its own, so every failed
 * attempt comes back here (spring-data-redis #2822: its recovery sleeps and recurses while holding the container's
 * lock, which deadlocks {@code stop()}).
 *
 * <p>As a {@link SmartLifecycle} that depends on the container it stops first (Spring stops dependents first),
 * interrupting the retry loop, so the loop never starts the container during or after the container's stop. One attempt can take about 6 s
 * (shared connect, pub/sub connect, registration wait); if {@code stopTimeout} expires first, the container's stop
 * overlaps that attempt but cannot deadlock.
 *
 * <p>Known limit: after a context {@code stop()}/{@code start()} nothing resubscribes; this project does not do that.
 */
class CatalogChangedSubscriber implements ApplicationListener<ApplicationReadyEvent>, SmartLifecycle, DisposableBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(CatalogChangedSubscriber.class);

    private final RedisMessageListenerContainer container;
    private final Duration firstDelay;
    private final Duration longestDelay;
    private final Duration stopTimeout;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "catalog-change-subscriber");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean running;

    CatalogChangedSubscriber(
            RedisMessageListenerContainer container, Duration firstDelay, Duration longestDelay, Duration stopTimeout) {
        this.container = container;
        this.firstDelay = firstDelay;
        this.longestDelay = longestDelay;
        this.stopTimeout = stopTimeout;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (running) {
            executor.execute(this::subscribeWithBackoff);
        }
    }

    void subscribeWithBackoff() {
        Duration delay = firstDelay;
        boolean warned = false;
        while (running) {
            try {
                container.start();
                LOGGER.info("Subscribed to catalog change notifications");
                return;
            } catch (RuntimeException failure) {
                container.stop();
                if (!warned) {
                    LOGGER.warn(
                            "Valkey is unreachable ({}); catalog changes from other pods are not received yet,"
                                    + " retrying with backoff",
                            failure.getClass().getSimpleName());
                    warned = true;
                }
                if (!pause(delay)) {
                    return;
                }
                delay = delay.multipliedBy(2).compareTo(longestDelay) > 0 ? longestDelay : delay.multipliedBy(2);
            }
        }
    }

    private boolean pause(Duration delay) {
        try {
            Thread.sleep(delay);
            return running;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void stop() {
        running = false;
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(stopTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                LOGGER.warn(
                        "The catalog change subscriber is still busy after {}; closing the container anyway",
                        stopTimeout);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
