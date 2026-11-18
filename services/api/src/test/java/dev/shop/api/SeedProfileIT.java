package dev.shop.api;

import static dev.shop.api.support.HttpGet.get;
import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.StubIdentityProvider;
import dev.shop.api.support.TestInfrastructure;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
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
}
