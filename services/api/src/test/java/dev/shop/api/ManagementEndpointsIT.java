package dev.shop.api;

import static dev.shop.api.support.HttpGet.get;
import static dev.shop.api.support.HttpGet.statusOf;
import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

@IntegrationTest
class ManagementEndpointsIT {

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Test
    void doesNotServeActuatorOnTheApplicationPort() {
        assertThat(statusOf(serverPort, "/actuator/health")).isEqualTo(401);
    }

    @Test
    void exposesOnlyHealthOnTheManagementPort() {
        assertThat(statusOf(managementPort, "/actuator/health")).isEqualTo(200);
        assertThat(statusOf(managementPort, "/actuator/env")).isEqualTo(401);
        assertThat(statusOf(managementPort, "/actuator/info")).isEqualTo(401);
    }

    @Test
    void discoveryPageOnTheManagementPortListsOnlyHealthLinks() {
        var response = get(managementPort, "/actuator");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("/actuator/health").doesNotContain("/actuator/env", "/actuator/info");
    }

    @Test
    void unknownApiPathIsDeniedByDefault() {
        assertThat(statusOf(serverPort, "/api/unknown")).isEqualTo(401);
    }
}
