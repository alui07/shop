package dev.shop.api;

import static dev.shop.api.support.HttpGet.statusOf;
import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.TestInfrastructure;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DirtiesContext
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "spring.datasource.hikari.connection-timeout=250",
            "spring.datasource.hikari.validation-timeout=250"
        })
@TestInfrastructure
class ReadinessIT {

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private PostgreSQLContainer postgres;

    @Test
    void readinessFailsWhenTheDatabaseGoesAwayWhileLivenessStaysUp() {
        assertThat(statusOf(managementPort, "/actuator/health/readiness")).isEqualTo(200);

        postgres.stop();

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(statusOf(managementPort, "/actuator/health/readiness"))
                        .isEqualTo(503));
        assertThat(statusOf(managementPort, "/actuator/health/liveness")).isEqualTo(200);
    }
}
