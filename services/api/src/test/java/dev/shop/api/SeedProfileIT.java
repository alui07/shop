package dev.shop.api;

import static dev.shop.api.support.HttpGet.get;
import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@ActiveProfiles("seed")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@Import(PostgresTestConfiguration.class)
class SeedProfileIT {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PostgreSQLContainer postgres;

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
        assertThat(get(port, "/v3/api-docs").statusCode()).isEqualTo(404);
    }

    @Test
    void aDatabaseMigratedWithSeedDataStillStartsWithoutTheSeedProfile() {
        try (ConfigurableApplicationContext withoutSeed = new SpringApplicationBuilder(ApiApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("default")
                .run(
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
