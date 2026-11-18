package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AuthIT;
import dev.shop.api.support.Identity;
import dev.shop.api.support.TestBrowser;
import java.net.http.HttpResponse;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Every route as an anonymous caller, a customer and an admin: the authorisation rules in one table. */
class AuthorizationMatrixIT extends AuthIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static Stream<Arguments> safeRequests() {
        return Stream.of(
                Arguments.of("/api/status", 200, 200, 200),
                Arguments.of("/api/categories", 200, 200, 200),
                Arguments.of("/api/products", 200, 200, 200),
                Arguments.of("/api/products/missing", 404, 404, 404),
                Arguments.of("/api/me", 200, 200, 200),
                Arguments.of("/api/admin/products", 401, 403, 200),
                Arguments.of("/api/admin/products?size=101", 401, 403, 400),
                Arguments.of("/api/admin/nothing", 401, 403, 404),
                Arguments.of("/api/unknown", 401, 403, 403),
                Arguments.of("/actuator/health", 401, 403, 403),
                Arguments.of("/v3/api-docs", 200, 200, 200),
                Arguments.of("/login", 401, 403, 403),
                Arguments.of("/logout", 401, 403, 403),
                Arguments.of("/error", 401, 403, 403));
    }

    static Stream<Arguments> unsafeRequests() {
        return Stream.of(
                Arguments.of("POST", "/api/admin/products", 401, 403, 405),
                Arguments.of("PUT", "/api/products/x", 401, 403, 403),
                Arguments.of("DELETE", "/api/me", 401, 403, 403),
                Arguments.of("POST", "/api/unknown", 401, 403, 403));
    }

    @ParameterizedTest(name = "GET {0}")
    @MethodSource("safeRequests")
    void safeRequestsFollowTheRules(String path, int anonymous, int customer, int admin) {
        assertThat(statusFor(browser(), "GET", path, true)).as("anonymous").isEqualTo(anonymous);
        assertThat(statusFor(signedInAs(Identity.customer()), "GET", path, true))
                .as("customer")
                .isEqualTo(customer);
        assertThat(statusFor(signedInAs(Identity.admin()), "GET", path, true))
                .as("admin")
                .isEqualTo(admin);
    }

    @ParameterizedTest(name = "{0} {1} with a CSRF token")
    @MethodSource("unsafeRequests")
    void unsafeRequestsWithACsrfTokenFollowTheRules(
            String method, String path, int anonymous, int customer, int admin) {
        assertThat(statusFor(browser(), method, path, true)).as("anonymous").isEqualTo(anonymous);
        assertThat(statusFor(signedInAs(Identity.customer()), method, path, true))
                .as("customer")
                .isEqualTo(customer);
        assertThat(statusFor(signedInAs(Identity.admin()), method, path, true))
                .as("admin")
                .isEqualTo(admin);
    }

    @ParameterizedTest(name = "{0} {1} without a CSRF token")
    @MethodSource("unsafeRequests")
    void unsafeRequestsWithoutACsrfTokenAreForbiddenForEveryone(String method, String path) throws Exception {
        for (Supplier<TestBrowser> who : java.util.List.<Supplier<TestBrowser>>of(
                this::browser, () -> signedInAs(Identity.customer()), () -> signedInAs(Identity.admin()))) {
            TestBrowser browser = who.get();
            browser.get("/api/me");

            HttpResponse<String> response = browser.send(method, path, null);

            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(JSON.readTree(response.body()).get("detail").asString())
                    .isEqualTo("CSRF token missing or invalid");
        }
    }

    @Test
    void failuresAreProblemDetails() throws Exception {
        HttpResponse<String> unauthorised = browser().get("/api/admin/products");
        HttpResponse<String> forbidden = signedInAs(Identity.customer()).get("/api/admin/products");

        assertProblem(unauthorised, 401, "Unauthorized", "Authentication required");
        assertProblem(forbidden, 403, "Forbidden", "Access is denied");
    }

    private static void assertProblem(HttpResponse<String> response, int status, String title, String detail)
            throws Exception {
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("type").asString()).isEqualTo("about:blank");
        assertThat(body.get("title").asString()).isEqualTo(title);
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("detail").asString()).isEqualTo(detail);
    }

    private static int statusFor(TestBrowser browser, String method, String path, boolean withCsrf) {
        browser.get("/api/me");
        HttpResponse<String> response = withCsrf && !method.equals("GET")
                ? browser.send(method, path, null, "X-XSRF-TOKEN", browser.cookie(TestBrowser.CSRF_COOKIE))
                : browser.send(method, path, null);
        return response.statusCode();
    }
}
