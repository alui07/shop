package dev.shop.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.ShopIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A pod that starts while Valkey is unreachable must come up and serve the catalog, and must pick up cross-pod
 * invalidation once Valkey is back. Valkey is paused rather than stopped so its mapped port survives the outage.
 */
@DirtiesContext
@TestPropertySource(
        properties = {"spring.cache.type=caffeine", "spring.cache.caffeine.spec=maximumSize=2000,expireAfterWrite=5m"})
class CatalogCacheValkeyDownAtStartIT extends ShopIT {

    @Autowired
    private ProductAdministration products;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private PostgreSQLContainer postgres;

    @Autowired
    private GenericContainer<?> valkey;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    @WithMockUser(roles = "ADMIN")
    void aPodStartsAndServesWithoutValkeyAndReceivesInvalidationOnceValkeyReturns() throws Exception {
        var docker = valkey.getDockerClient();
        docker.pauseContainerCmd(valkey.getContainerId()).exec();
        boolean paused = true;
        try (ConfigurableApplicationContext other =
                SecondApiContext.start(postgres, valkey, "maximumSize=2000,expireAfterWrite=5m")) {
            CatalogService otherPod = other.getBean(CatalogService.class);
            assertThat(catalogStatus(other)).isEqualTo(200);
            assertThat(otherPod.product(LAMP).name()).isEqualTo("Desk Lamp");

            docker.unpauseContainerCmd(valkey.getContainerId()).exec();
            paused = false;

            Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> otherPodSubscribed());
            new TransactionTemplate(transactions)
                    .executeWithoutResult(status -> products.update(
                            lampId, 0, new ProductChanges("Renamed Lamp", "Bright", 1500, "office", true)));

            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(
                            () -> assertThat(otherPod.product(LAMP).name()).isEqualTo("Renamed Lamp"));
        } finally {
            if (paused) {
                docker.unpauseContainerCmd(valkey.getContainerId()).exec();
            }
        }
    }

    /** Publishing returns the receiver count; the main context is always subscribed, so two receivers means the other pod is too. */
    private boolean otherPodSubscribed() {
        Long receivers = redisTemplate.convertAndSend(CatalogCacheInvalidation.CHANNEL, "probe");
        return receivers != null && receivers >= 2;
    }

    private static int catalogStatus(ConfigurableApplicationContext pod) throws Exception {
        int port = ((WebServerApplicationContext) pod).getWebServer().getPort();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/products"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }
}
