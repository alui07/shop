package dev.shop.api.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {

    private static final String POSTGRES_IMAGE_ENV = "POSTGRES_IMAGE";

    @Bean
    @ServiceConnection(name = "postgres")
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(
                DockerImageName.parse(requiredEnv(POSTGRES_IMAGE_ENV)).asCompatibleSubstituteFor("postgres"));
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Environment variable " + name + " must name the Postgres image");
        }
        return value;
    }
}
