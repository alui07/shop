package dev.shop.api.catalog;

import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Caching wraps the transaction (highest precedence), so a hit never opens one. Cache settings live in
 * {@code spring.cache.*}; {@code SPRING_CACHE_TYPE=none} turns the caches off without a code change. The listener
 * container does not start during refresh; {@link CatalogChangedSubscriber} starts it after readiness.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching(order = Ordered.HIGHEST_PRECEDENCE)
class CatalogCacheConfiguration {

    @Bean
    CatalogCacheInvalidation catalogCacheInvalidation(CacheManager caches, StringRedisTemplate redis) {
        return new CatalogCacheInvalidation(caches, redis);
    }

    @Bean
    RedisMessageListenerContainer catalogChangedListenerContainer(
            RedisConnectionFactory connectionFactory, CatalogCacheInvalidation invalidation) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setAutoStartup(false);
        // CatalogChangedSubscriber owns retries and Lettuce re-subscribes after a drop. The default unlimited
        // recovery sleeps and recurses under the container's lock, so stop() would deadlock behind it.
        container.setRecoveryBackoff(new FixedBackOff(0, 0));
        // Belt and braces: the subscriber depends on this container, and Spring stops dependents first.
        container.setPhase(SmartLifecycle.DEFAULT_PHASE - 1);
        container.addMessageListener(invalidation, new ChannelTopic(CatalogCacheInvalidation.CHANNEL));
        return container;
    }

    @Bean
    CatalogChangedSubscriber catalogChangedSubscriber(RedisMessageListenerContainer catalogChangedListenerContainer) {
        return new CatalogChangedSubscriber(
                catalogChangedListenerContainer, Duration.ofSeconds(1), Duration.ofSeconds(15), Duration.ofSeconds(5));
    }
}
