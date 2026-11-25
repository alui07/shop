package dev.shop.api.auth;

import dev.shop.api.support.AdminClient;
import dev.shop.api.support.Identity;
import dev.shop.api.support.ShopIT;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;

/** Turns the limiter on against its own Valkey and gives tests a view of the keys it keeps there. */
@TestPropertySource(properties = "shop.rate-limit.enabled=true")
@Import(RateLimitTestConfiguration.class)
abstract class RateLimitITSupport extends ShopIT {

    static final String CHECKOUT_BODY = "{\"cartVersion\":0,\"expectedTotalMinor\":0}";

    @Autowired
    @Qualifier("limiterValkey")
    protected GenericContainer<?> limiterValkey;

    private RedisClient inspector;
    private StatefulRedisConnection<String, String> inspection;

    @BeforeEach
    void connectInspector() {
        inspector = RedisClient.create("redis://" + limiterValkey.getHost() + ":" + limiterValkey.getMappedPort(6379));
        inspection = inspector.connect();
        inspection.sync().flushall();
    }

    @AfterEach
    void disconnectInspector() {
        inspection.close();
        inspector.shutdown();
    }

    protected RedisCommands<String, String> limiterStore() {
        return inspection.sync();
    }

    protected List<String> limiterKeys() {
        return limiterStore().keys("shop:rate:*");
    }

    protected static String newSubject() {
        return "rl-" + UUID.randomUUID();
    }

    protected AdminClient signedInCustomer(String subject) {
        return customer(subject);
    }

    protected AdminClient signedInAdmin() {
        return new AdminClient(signedInAs(Identity.admin().withSubject(newSubject())));
    }

    protected static HttpResponse<String> checkout(AdminClient client) {
        return client.post(
                "/api/checkout",
                CHECKOUT_BODY,
                "Idempotency-Key",
                UUID.randomUUID().toString());
    }
}
