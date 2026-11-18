package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AuthIT;
import dev.shop.api.support.Identity;
import dev.shop.api.support.TestBrowser;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;

@DirtiesContext
class ValkeyOutageIT extends AuthIT {

    @Autowired
    private GenericContainer<?> valkey;

    @LocalManagementPort
    private int managementPort;

    @Test
    void thePublicCatalogKeepsServingWithoutACookieAndSessionRequestsGetA503() throws Exception {
        TestBrowser signedIn = signedInAs(Identity.customer());

        valkey.stop();

        TestBrowser visitor = browser();
        assertThat(visitor.get("/api/products").statusCode()).isEqualTo(200);
        assertThat(visitor.get("/api/status").statusCode()).isEqualTo(200);

        HttpResponse<String> withCookie = signedIn.get("/api/me");
        assertThat(withCookie.statusCode()).isEqualTo(503);
        assertThat(withCookie.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        assertThat(JsonMapper.builder()
                        .build()
                        .readTree(withCookie.body())
                        .get("status")
                        .asInt())
                .isEqualTo(503);

        assertThat(visitor.get("/oauth2/authorization/keycloak").statusCode()).isEqualTo(503);
        assertThat(dev.shop.api.support.HttpGet.statusOf(managementPort, "/actuator/health/readiness"))
                .isEqualTo(200);
    }
}
