package dev.shop.api.catalog;

import java.util.Arrays;
import java.util.stream.Stream;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** A second API instance on the same Postgres and Valkey containers, standing in for another pod. */
public final class SecondApiContext {

    private SecondApiContext() {}

    public static ConfigurableApplicationContext start(
            PostgreSQLContainer postgres, GenericContainer<?> valkey, String cacheSpec, String... extraArguments) {
        String[] arguments = new String[] {
            "--server.port=0",
            "--management.server.port=0",
            "--spring.main.banner-mode=off",
            "--spring.flyway.enabled=false",
            "--spring.datasource.url=" + postgres.getJdbcUrl(),
            "--spring.datasource.username=" + postgres.getUsername(),
            "--spring.datasource.password=" + postgres.getPassword(),
            "--spring.data.redis.host=" + valkey.getHost(),
            "--spring.data.redis.port=" + valkey.getMappedPort(6379),
            "--spring.cache.type=caffeine",
            "--spring.cache.caffeine.spec=" + cacheSpec,
            "--shop.jobs.enabled=false",
            "--shop.payment.provider=fake",
            "--shop.payment.webhook-secret=test-webhook-secret-test-webhook-secret",
            "--shop.auth.public-origin=https://shop.example.test",
            "--shop.auth.issuer=https://idp.example.test/realms/shop",
            "--shop.auth.internal-realm-url=http://localhost:1/realms/shop",
            "--shop.auth.client-id=shop-api",
            "--shop.auth.client-secret=test-client-secret-test-client-secret"
        };
        return new SpringApplicationBuilder(dev.shop.api.ApiApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(Stream.concat(Arrays.stream(arguments), Arrays.stream(extraArguments))
                        .toArray(String[]::new));
    }
}
