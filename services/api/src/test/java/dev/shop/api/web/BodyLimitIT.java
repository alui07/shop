package dev.shop.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AdminClient;
import dev.shop.api.support.Identity;
import dev.shop.api.support.ShopIT;
import dev.shop.api.support.TestBrowser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/** Customer request bodies are capped at 16 KiB, declared or streamed; admin bodies keep their own bounds. */
class BodyLimitIT extends ShopIT {

    private static final int LIMIT = 16 * 1024;
    private static final String TOO_LARGE = "tag:shop.dev,2026:body-too-large";
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    static Stream<Arguments> customerRoutesThatReadABody() {
        return Stream.of(
                Arguments.of("PUT", "/api/cart/lines/desk-lamp", "{\"quantity\":1}"),
                Arguments.of("POST", "/api/checkout", "{\"cartVersion\":0,\"expectedTotalMinor\":0}"));
    }

    static Stream<Arguments> customerUnsafeRoutes() {
        return Stream.concat(
                customerRoutesThatReadABody(),
                Stream.of(
                        Arguments.of("DELETE", "/api/cart/lines/desk-lamp", ""),
                        Arguments.of("POST", "/api/orders/" + UUID.randomUUID() + "/cancel", "")));
    }

    @ParameterizedTest(name = "{0} {1} with a declared length over the limit")
    @MethodSource("customerUnsafeRoutes")
    void aDeclaredLengthOverTheLimitIsRefusedBeforeAnythingIsRead(String method, String path, String json) {
        TestBrowser browser = signedInAs(Identity.customer());
        browser.get("/api/me");

        HttpResponse<String> response = send(browser, method, path, padded(json, LIMIT + 1), false);

        assertTooLarge(response);
    }

    @ParameterizedTest(name = "{0} {1} streamed without a length")
    @MethodSource("customerRoutesThatReadABody")
    void aStreamedBodyOverTheLimitIsRefusedAtOneByteOver(String method, String path, String json) {
        TestBrowser browser = signedInAs(Identity.customer());
        browser.get("/api/me");
        putLineFirst(browser);

        HttpResponse<String> response = send(browser, method, path, padded(json, LIMIT + 1), true);

        assertTooLarge(response);
    }

    @ParameterizedTest(name = "{0} {1} at exactly the limit")
    @MethodSource("customerRoutesThatReadABody")
    void aBodyOfExactlyTheLimitIsAccepted(String method, String path, String json) {
        TestBrowser browser = signedInAs(Identity.customer());
        browser.get("/api/me");

        HttpResponse<String> declared = send(browser, method, path, padded(json, LIMIT), false);
        HttpResponse<String> streamed = send(browser, method, path, padded(json, LIMIT), true);

        assertThat(declared.statusCode()).isNotEqualTo(413);
        assertThat(streamed.statusCode()).isNotEqualTo(413);
        assertThat(declared.statusCode()).isLessThan(500);
        assertThat(streamed.statusCode()).isLessThan(500);
    }

    @Test
    void anAnonymousCallerGetsTheSizeRefusalBeforeAnyAuthenticationAnswer() {
        TestBrowser browser = browser();
        browser.get("/api/me");

        HttpResponse<String> response = send(browser, "POST", "/api/checkout", padded("{}", LIMIT + 1), false);

        assertTooLarge(response);
    }

    @Test
    void adminBodiesAreNotCapped() {
        TestBrowser browser = signedInAs(Identity.admin());
        browser.get("/api/me");
        String body = "{\"slug\":\"x\",\"pad\":\"" + "a".repeat(2 * LIMIT) + "\"}";

        HttpResponse<String> response = send(browser, "POST", "/api/admin/products", body, false);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(AdminClient.json(response).get("type").asString()).isEqualTo("tag:shop.dev,2026:validation");
    }

    @Test
    void aSmallBodyStillWorksAfterAnOversizedOne() {
        AdminClient client = customer("limit-recover");

        assertThat(putLine(client, LAMP, 1).get("version").asLong()).isEqualTo(1);
    }

    private void putLineFirst(TestBrowser browser) {
        new AdminClient(browser).put("/api/cart/lines/" + LAMP, "{\"quantity\":1}");
    }

    private static void assertTooLarge(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        JsonNode problem = AdminClient.json(response);
        assertThat(problem.get("type").asString()).isEqualTo(TOO_LARGE);
        assertThat(problem.get("status").asInt()).isEqualTo(413);
        assertThat(problem.get("errors")).isEmpty();
    }

    private static String padded(String json, int length) {
        return json.isEmpty() ? " ".repeat(length) : json + " ".repeat(length - json.length());
    }

    private HttpResponse<String> send(TestBrowser browser, String method, String path, String body, boolean chunked) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpRequest.BodyPublisher publisher = chunked
                ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))
                : HttpRequest.BodyPublishers.ofByteArray(bytes);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, publisher)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("X-XSRF-TOKEN", browser.cookie(TestBrowser.CSRF_COOKIE));
        String session = browser.cookie(TestBrowser.SESSION_COOKIE);
        request.header(
                "Cookie",
                (session == null ? "" : TestBrowser.SESSION_COOKIE + "=" + session + "; ") + TestBrowser.CSRF_COOKIE
                        + "=" + browser.cookie(TestBrowser.CSRF_COOKIE));
        try {
            return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
