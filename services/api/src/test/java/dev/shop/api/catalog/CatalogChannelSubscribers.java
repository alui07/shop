package dev.shop.api.catalog;

import java.util.List;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Counts the subscribers of the invalidation channel without publishing, so no cache is cleared by the probe. */
final class CatalogChannelSubscribers {

    private CatalogChannelSubscribers() {}

    static long count(StringRedisTemplate redis) {
        Object reply = redis.execute((RedisCallback<Object>) connection -> connection
                .scriptingCommands()
                .eval(
                        "return redis.call('PUBSUB', 'NUMSUB', KEYS[1])".getBytes(),
                        ReturnType.MULTI,
                        1,
                        CatalogCacheInvalidation.CHANNEL.getBytes()));
        return reply instanceof List<?> pairs && pairs.size() == 2 && pairs.get(1) instanceof Number number
                ? number.longValue()
                : 0;
    }
}
