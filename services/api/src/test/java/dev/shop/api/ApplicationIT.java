package dev.shop.api;

import static dev.shop.api.support.HttpGet.get;
import static dev.shop.api.support.HttpGet.statusOf;
import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.IntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class ApplicationIT {

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private BuildProperties buildProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appliesFlywayBaselineMigration() {
        Integer rows = jdbcTemplate.queryForObject("SELECT count(*) FROM schema_info", Integer.class);

        assertThat(rows).isEqualTo(1);
    }

    @Test
    void servesStatusWithBuildVersion() {
        HttpResponse<String> response = get(serverPort, "/api/status");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"status\":\"ok\"")
                .contains("\"version\":\"" + buildProperties.getVersion() + "\"");
    }

    @Test
    void reportsReadinessAndLiveness() {
        assertThat(statusOf(managementPort, "/actuator/health/readiness")).isEqualTo(200);
        assertThat(statusOf(managementPort, "/actuator/health/liveness")).isEqualTo(200);
    }
}
