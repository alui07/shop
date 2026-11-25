package dev.shop.api.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * After an admin change commits, this pod clears its catalog caches and tells every pod to do the same. The message
 * only triggers a clear; its payload is never read. A failed publish is logged once and swallowed: the cache expiry
 * bounds how long another pod stays stale.
 */
class CatalogCacheInvalidation implements MessageListener {

    static final String CHANNEL = "shop:catalog-changed";

    private static final Logger LOGGER = LoggerFactory.getLogger(CatalogCacheInvalidation.class);

    private final CacheManager caches;
    private final StringRedisTemplate redis;

    CatalogCacheInvalidation(CacheManager caches, StringRedisTemplate redis) {
        this.caches = caches;
        this.redis = redis;
    }

    /** Must be called inside a transaction; nothing happens if it rolls back. */
    void afterCommit() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                clearLocal();
                publish();
            }
        });
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        clearLocal();
    }

    void clearLocal() {
        caches.getCacheNames().forEach(name -> caches.getCache(name).clear());
    }

    private void publish() {
        try {
            redis.convertAndSend(CHANNEL, "changed");
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "Catalog change was not broadcast ({}); other pods stay stale until the cache expires",
                    failure.getClass().getSimpleName());
        }
    }
}
