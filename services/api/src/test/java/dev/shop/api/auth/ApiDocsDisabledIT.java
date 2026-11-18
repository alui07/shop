package dev.shop.api.auth;

import static dev.shop.api.support.HttpGet.statusOf;
import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.TestInfrastructure;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** The default profile does not publish the API document, and security does not open the path either. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@TestInfrastructure
class ApiDocsDisabledIT {

    @LocalServerPort
    private int port;

    @Test
    void theOpenApiDocumentIsNotReachable() {
        assertThat(statusOf(port, "/v3/api-docs")).isEqualTo(401);
    }
}
