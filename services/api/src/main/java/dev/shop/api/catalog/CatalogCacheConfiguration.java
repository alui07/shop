package dev.shop.api.catalog;

import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

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
        container.addMessageListener(invalidation, new ChannelTopic(CatalogCacheInvalidation.CHANNEL));
        return container;
    }

    @Bean
    CatalogChangedSubscriber catalogChangedSubscriber(RedisMessageListenerContainer catalogChangedListenerContainer) {
        return new CatalogChangedSubscriber(
                catalogChangedListenerContainer, Duration.ofSeconds(1), Duration.ofSeconds(15));
    }
}
