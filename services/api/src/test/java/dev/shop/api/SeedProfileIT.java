package dev.shop.api;

import static dev.shop.api.support.HttpGet.get;
import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.StubIdentityProvider;
import dev.shop.api.support.TestInfrastructure;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@ActiveProfiles("seed")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@TestInfrastructure
class SeedProfileIT {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PostgreSQLContainer postgres;

    @Autowired
    private GenericContainer<?> valkey;

    @Autowired
    private StubIdentityProvider identityProvider;

    @Test
    void seedsSixCategoriesAndAtLeastThreePagesOfActiveProducts() throws Exception {
        long categories =
                jdbc.sql("SELECT count(*) FROM category").query(Long.class).single();
        var firstPage =
                JsonMapper.builder().build().readTree(get(port, "/api/products").body());

        assertThat(categories).isEqualTo(6);
        assertThat(firstPage.get("totalItems").asLong()).isGreaterThanOrEqualTo(50);
        assertThat(firstPage.get("totalPages").asInt()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void doesNotServeTheOpenApiDocumentOutsideTheDevProfile() {
        assertThat(get(port, "/v3/api-docs").statusCode()).isEqualTo(401);
    }

    @Test
    void aDatabaseMigratedWithSeedDataStillStartsWithoutTheSeedProfile() {
        try (ConfigurableApplicationContext withoutSeed = new SpringApplicationBuilder(ApiApplication.class)
                .web(WebApplicationType.SERVLET)
                .profiles("default")
                .run(
                        "--server.port=0",
                        "--management.server.port=0",
                        "--shop.auth.public-origin=https://shop.example.test",
                        "--shop.auth.issuer=" + StubIdentityProvider.ISSUER,
                        "--shop.auth.internal-realm-url=" + identityProvider.internalRealmUrl(),
                        "--shop.auth.client-id=shop-api",
                        "--shop.auth.client-secret=" + StubIdentityProvider.CLIENT_SECRET,
                        "--shop.payment.provider=fake",
                        "--shop.payment.webhook-secret=test-webhook-secret-test-webhook-secret",
                        "--spring.data.redis.host=" + valkey.getHost(),
                        "--spring.data.redis.port=" + valkey.getMappedPort(6379),
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword())) {
            assertThat(withoutSeed.isRunning()).isTrue();
            assertThat(withoutSeed
                            .getBean(JdbcClient.class)
                            .sql("SELECT count(*) FROM product")
                            .query(Long.class)
                            .single())
                    .isGreaterThanOrEqualTo(50);
        }
    }

    @Test
    void everySeedProductHasNonZeroStock() {
        assertThat(jdbc.sql("SELECT count(*) FROM stock WHERE on_hand = 0")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM stock").query(Long.class).single())
                .isEqualTo(jdbc.sql("SELECT count(*) FROM product")
                        .query(Long.class)
                        .single());
    }

    @Test
    void rerunningTheSeedKeepsWhatAnAdminChangedAndRestoresWhatNoOneTouched() throws Exception {
        long edited = stockProductId(0);
        long reservedAbove = stockProductId(1);
        long untouched = stockProductId(2);
        int seededUntouched = onHand(untouched);
        jdbc.sql("UPDATE stock SET on_hand = 3, version = 1 WHERE product_id = :id")
                .param("id", edited)
                .update();
        jdbc.sql("UPDATE stock SET on_hand = 900, reserved = 800 WHERE product_id = :id")
                .param("id", reservedAbove)
                .update();
        jdbc.sql("UPDATE stock SET on_hand = 999 WHERE product_id = :id")
                .param("id", untouched)
                .update();
        var productVersions = jdbc.sql("SELECT slug, version FROM product ORDER BY slug")
                .query()
                .listOfRows();
        var stockVersions = jdbc.sql("SELECT product_id, version FROM stock ORDER BY product_id")
                .query()
                .listOfRows();

        runTheSeedScript();

        assertThat(onHand(edited)).isEqualTo(3);
        assertThat(jdbc.sql("SELECT version FROM stock WHERE product_id = :id")
                        .param("id", edited)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(onHand(reservedAbove)).isEqualTo(900);
        assertThat(onHand(untouched)).isEqualTo(seededUntouched);
        assertThat(jdbc.sql("SELECT slug, version FROM product ORDER BY slug")
                        .query()
                        .listOfRows())
                .isEqualTo(productVersions);
        assertThat(jdbc.sql("SELECT product_id, version FROM stock ORDER BY product_id")
                        .query()
                        .listOfRows())
                .isEqualTo(stockVersions);

        runTheSeedScript();

        assertThat(onHand(untouched)).isEqualTo(seededUntouched);
        assertThat(onHand(edited)).isEqualTo(3);
        jdbc.sql("UPDATE stock SET version = 0, on_hand = 20, reserved = 0 WHERE product_id IN (:ids)")
                .param("ids", List.of(edited, reservedAbove))
                .update();
    }

    @Test
    void theSeedUpsertBumpsTheVersionOfAProductItChangesAndReactivatesIt() throws Exception {
        jdbc.sql("UPDATE product SET name = 'Edited', active = false WHERE slug = 'oak-desk'")
                .update();

        runTheSeedScript();

        var row = jdbc.sql("SELECT name, active, version FROM product WHERE slug = 'oak-desk'")
                .query()
                .singleRow();
        assertThat(row)
                .containsEntry("name", "Oak Desk")
                .containsEntry("active", true)
                .containsEntry("version", 1L);
    }

    private long stockProductId(int offset) {
        return jdbc.sql("SELECT product_id FROM stock ORDER BY product_id OFFSET :offset LIMIT 1")
                .param("offset", offset)
                .query(Long.class)
                .single();
    }

    private int onHand(long productId) {
        return jdbc.sql("SELECT on_hand FROM stock WHERE product_id = :id")
                .param("id", productId)
                .query(Integer.class)
                .single();
    }

    private void runTheSeedScript() throws Exception {
        new ResourceDatabasePopulator(new ClassPathResource("db/seed/R__seed_catalog.sql")).execute(dataSource);
    }
}
