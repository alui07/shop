package dev.shop.api.catalog;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Starts the catalog-change subscription once the application is ready, off the startup thread, and retries with
 * backoff until Valkey answers. Valkey is therefore never a startup dependency: until the subscription is up, this
 * pod serves from its own caches and the cache expiry bounds how stale they get. After the first success the
 * container itself re-subscribes when the connection drops.
 */
class CatalogChangedSubscriber implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(CatalogChangedSubscriber.class);

    private final RedisMessageListenerContainer container;
    private final Duration firstDelay;
    private final Duration longestDelay;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "catalog-change-subscriber");
        thread.setDaemon(true);
        return thread;
    });

    CatalogChangedSubscriber(RedisMessageListenerContainer container, Duration firstDelay, Duration longestDelay) {
        this.container = container;
        this.firstDelay = firstDelay;
        this.longestDelay = longestDelay;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        executor.execute(this::subscribeWithBackoff);
    }

    void subscribeWithBackoff() {
        Duration delay = firstDelay;
        boolean warned = false;
        while (true) {
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

    private static boolean pause(Duration delay) {
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
