package dev.shop.api.auth;

import io.github.bucket4j.distributed.proxy.ProxyManager;

/** Where the limiter's buckets live. It is asked on every request, so it must not block for long. */
interface LimiterBackend {

    /** The bucket store, connecting on first use; throws a Lettuce exception while the store cannot be connected to. */
    ProxyManager<String> proxies();
}
