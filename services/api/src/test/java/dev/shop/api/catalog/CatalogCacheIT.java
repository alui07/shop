package dev.shop.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AdminClient;
import dev.shop.api.support.Identity;
import dev.shop.api.support.ShopIT;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

@TestPropertySource(properties = "spring.cache.type=caffeine")
class CatalogCacheIT extends ShopIT {

    /** Goes through {@code CatalogService.search()}, which is {@code @Transactional}: caching must wrap it. */
    private static final String TRANSACTIONAL_LISTING = "/api/products?category=office";

    private static final ProductChanges RENAMED = new ProductChanges("Renamed Lamp", "Bright", 1500, "office", true);

    @Autowired
    private CacheManager caches;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private ProductAdministration products;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private RedisConnectionFactory redisConnections;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private PostgreSQLContainer postgres;

    @Autowired
    private GenericContainer<?> valkey;

    @BeforeEach
    void startWithEmptyCaches() {
        caches.getCacheNames().forEach(name -> caches.getCache(name).clear());
    }

    private String lampName() {
        return AdminClient.json(browser().get("/api/products/" + LAMP))
                .get("name")
                .asString();
    }

    private void renameLampDirectly(String name) {
        jdbc.sql("UPDATE product SET name = :name WHERE slug = :slug")
                .param("name", name)
                .param("slug", LAMP)
                .update();
    }

    private void adminUpdatesLampInTransaction(boolean rollBack) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.executeWithoutResult(status -> {
            products.update(lampId, 0, RENAMED);
            if (rollBack) {
                status.setRollbackOnly();
            }
        });
    }

    private double counted(String cache, String result) {
        return meters.get("cache.gets")
                .tag("cache", cache)
                .tag("result", result)
                .functionCounter()
                .count();
    }

    @Test
    void aDirectUpdateIsNotVisibleWithinTheTimeToLive() {
        assertThat(lampName()).isEqualTo("Desk Lamp");

        renameLampDirectly("Changed Behind The Cache");

        assertThat(lampName()).isEqualTo("Desk Lamp");
    }

    @Test
    void categoriesAndCategoryListingsAreCachedButTextSearchIsNot() {
        browser().get("/api/categories");
        browser().get("/api/products?category=office");
        jdbc.sql("UPDATE category SET name = 'Changed'").update();
        renameLampDirectly("Changed Lamp");

        JsonNode categories = AdminClient.json(browser().get("/api/categories"));
        JsonNode listing = AdminClient.json(browser().get("/api/products?category=office"));
        JsonNode search = AdminClient.json(browser().get("/api/products?q=changed"));

        assertThat(categories.get(0).get("name").asString()).isEqualTo("Office");
        assertThat(listing.toString()).contains("Desk Lamp").doesNotContain("Changed Lamp");
        assertThat(search.get("totalItems").asLong()).isEqualTo(1);
    }

    @Test
    void anUnknownProductIsNotCached() {
        assertThat(browser().get("/api/products/late-arrival").statusCode()).isEqualTo(404);
        CatalogFixtures_addProduct("late-arrival");

        assertThat(browser().get("/api/products/late-arrival").statusCode()).isEqualTo(200);
    }

    private void CatalogFixtures_addProduct(String slug) {
        dev.shop.api.support.CatalogFixtures.addProduct(jdbc, slug, "Late", "Arrival", 100, "office", true);
    }

    @Test
    void anAdminUpdateThroughTheApiIsVisibleRightAfterTheCommit() {
        AdminClient admin = new AdminClient(signedInAs(Identity.admin()));
        assertThat(lampName()).isEqualTo("Desk Lamp");

        HttpResponse<String> response = admin.put(
                "/api/admin/products/" + lampId,
                "{\"name\":\"Admin Lamp\",\"description\":\"Bright\",\"priceMinor\":1500,\"categorySlug\":\"office\","
                        + "\"active\":true,\"version\":0}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(lampName()).isEqualTo("Admin Lamp");
    }

    @Test
    void anAdminCreateClearsTheListingCache() {
        AdminClient admin = new AdminClient(signedInAs(Identity.admin()));
        assertThat(AdminClient.json(browser().get("/api/products?category=office"))
                        .get("totalItems")
                        .asLong())
                .isEqualTo(2);

        HttpResponse<String> response = admin.post(
                "/api/admin/products",
                "{\"slug\":\"oak\",\"name\":\"Oak\",\"description\":\"d\",\"priceMinor\":10,\"categorySlug\":\"office\","
                        + "\"active\":true}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(AdminClient.json(browser().get("/api/products?category=office"))
                        .get("totalItems")
                        .asLong())
                .isEqualTo(3);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void aRolledBackAdminUpdateClearsNothingAndPublishesNothing() throws Exception {
        try (ChannelRecorder channel = new ChannelRecorder(redisConnections)) {
            assertThat(lampName()).isEqualTo("Desk Lamp");
            renameLampDirectly("Changed Behind The Cache");

            adminUpdatesLampInTransaction(true);
            assertThat(lampName()).isEqualTo("Desk Lamp");

            adminUpdatesLampInTransaction(false);
            assertThat(lampName()).isEqualTo("Renamed Lamp");
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> channel.messages.size() == 1);
            assertThat(channel.messages).hasSize(1);
        }
    }

    @Test
    void aCacheHitDoesNotTouchTheConnectionPool() {
        browser().get(TRANSACTIONAL_LISTING);
        double acquiresAfterMiss = acquireCount();

        browser().get(TRANSACTIONAL_LISTING);
        browser().get(TRANSACTIONAL_LISTING);

        assertThat(acquireCount()).isEqualTo(acquiresAfterMiss);
    }

    private double acquireCount() {
        return meters.get("hikaricp.connections.acquire").timer().count();
    }

    @Test
    void cacheGetsMetricsCountHitsAndMisses() {
        double hitsBefore = counted("product", "hit");
        double missesBefore = counted("product", "miss");

        lampName();
        lampName();

        assertThat(counted("product", "miss")).isEqualTo(missesBefore + 1);
        assertThat(counted("product", "hit")).isEqualTo(hitsBefore + 1);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void anAdminUpdateInOnePodIsVisibleInAnotherWithinOneSecond() {
        try (ConfigurableApplicationContext other =
                SecondApiContext.start(postgres, valkey, "maximumSize=2000,expireAfterWrite=30s,recordStats")) {
            CatalogService otherPod = other.getBean(CatalogService.class);
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> subscribers() >= 2);
            assertThat(otherPod.product(LAMP).name()).isEqualTo("Desk Lamp");

            adminUpdatesLampInTransaction(false);

            Awaitility.await()
                    .atMost(Duration.ofSeconds(1))
                    .untilAsserted(
                            () -> assertThat(otherPod.product(LAMP).name()).isEqualTo("Renamed Lamp"));
        }
    }

    /** Publishing returns the receiver count; a probe clears caches only, which is harmless. */
    private long subscribers() {
        Long receivers = redisTemplate.convertAndSend(CatalogCacheInvalidation.CHANNEL, "probe");
        return receivers == null ? 0 : receivers;
    }

    /** Collects what is published on the invalidation channel. */
    private static final class ChannelRecorder implements AutoCloseable {
        final List<String> messages = new CopyOnWriteArrayList<>();
        private final RedisMessageListenerContainer container = new RedisMessageListenerContainer();

        ChannelRecorder(RedisConnectionFactory connections) throws Exception {
            container.setConnectionFactory(connections);
            container.addMessageListener(
                    (message, pattern) -> messages.add(new String(message.getBody())),
                    new ChannelTopic(CatalogCacheInvalidation.CHANNEL));
            container.afterPropertiesSet();
            container.start();
        }

        @Override
        public void close() throws Exception {
            container.destroy();
        }
    }
}
