package dev.shop.api.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class ValkeyTestConfiguration {

    private static final String VALKEY_IMAGE_ENV = "VALKEY_IMAGE";
    private static final int VALKEY_PORT = 6379;

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> valkey() {
        String image = System.getenv(VALKEY_IMAGE_ENV);
        if (image == null || image.isBlank()) {
            throw new IllegalStateException("Environment variable " + VALKEY_IMAGE_ENV + " must name the Valkey image");
        }
        return new GenericContainer<>(DockerImageName.parse(image)).withExposedPorts(VALKEY_PORT);
    }
}
