package dev.shop.api.auth;

import java.net.URI;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** The minting profile cannot start behind a real hostname. */
@Component
@Profile("loadtest")
class LoadtestGuard {

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1");

    LoadtestGuard(ShopAuthProperties auth) {
        String host = URI.create(auth.publicOrigin()).getHost();
        if (!LOCAL_HOSTS.contains(host)) {
            throw new IllegalStateException(
                    "The loadtest profile requires shop.auth.public-origin on localhost or 127.0.0.1");
        }
    }
}
