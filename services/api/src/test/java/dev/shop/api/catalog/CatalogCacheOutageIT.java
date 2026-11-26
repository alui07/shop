package dev.shop.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.ShopIT;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(
        properties = {"spring.cache.type=caffeine", "spring.cache.caffeine.spec=maximumSize=2000,expireAfterWrite=15s"})
class CatalogCacheOutageIT extends ShopIT {

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
    void withValkeyDownTheWriteStillCommitsAndOtherPodsAreStaleForAtMostTheTimeToLive(CapturedOutput output) {
        try (ConfigurableApplicationContext other =
                SecondApiContext.start(postgres, valkey, "maximumSize=2000,expireAfterWrite=15s")) {
            CatalogService otherPod = other.getBean(CatalogService.class);
            Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .until(() -> CatalogChannelSubscribers.count(redisTemplate) >= 2);
            assertThat(otherPod.product(LAMP).name()).isEqualTo("Desk Lamp");
            valkey.stop();

            new TransactionTemplate(transactions)
                    .executeWithoutResult(status -> products.update(
                            lampId, 0, new ProductChanges("Renamed Lamp", "Bright", 1500, "office", true)));

            assertThat(jdbc.sql("SELECT name FROM product WHERE id = :id")
                            .param("id", lampId)
                            .query(String.class)
                            .single())
                    .isEqualTo("Renamed Lamp");
            assertThat(output.getAll()).contains("Catalog change was not broadcast");
            assertThat(otherPod.product(LAMP).name()).isEqualTo("Desk Lamp");
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .untilAsserted(
                            () -> assertThat(otherPod.product(LAMP).name()).isEqualTo("Renamed Lamp"));
        }
    }
}
