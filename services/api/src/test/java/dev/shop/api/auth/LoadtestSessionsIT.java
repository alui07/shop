package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AdminClient;
import dev.shop.api.support.Identity;
import dev.shop.api.support.ShopIT;
import dev.shop.api.support.TestBrowser;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

@ActiveProfiles("loadtest")
@TestPropertySource(
        properties = {
            "shop.auth.public-origin=http://localhost",
            "shop.loadtest.token=" + LoadtestSessionsIT.TOKEN,
            "shop.loadtest.max-mints-per-second=1000"
        })
@ExtendWith(OutputCaptureExtension.class)
class LoadtestSessionsIT extends ShopIT {

    static final String TOKEN = "loadtest-token-loadtest-token-loadtest-token";
    private static final String PATH = "/api/loadtest/sessions";

    @Autowired
    private RedisConnectionFactory redisConnections;

    private HttpResponse<String> mint(TestBrowser browser) {
        return browser.post(PATH, null, "Shop-Loadtest-Token", TOKEN);
    }

    @Test
    void mintsACustomerSessionTheApiAccepts() {
        TestBrowser browser = browser();

        HttpResponse<String> response = mint(browser);

        assertThat(response.statusCode()).isEqualTo(201);
        String subject = AdminClient.json(response).get("subject").asString();
        assertThat(subject).matches("loadtest-[0-9a-f-]{36}");
        assertThat(browser.cookie(TestBrowser.SESSION_COOKIE)).isNotNull();
        JsonNode me = AdminClient.json(browser.get("/api/me"));
        assertThat(me.get("authenticated").asBoolean()).isTrue();
        assertThat(me.get("subject").asString()).isEqualTo(subject);
        assertThat(me.get("email").asString()).isEqualTo(subject + "@loadtest.invalid");
        assertThat(me.get("roles").valueStream().map(JsonNode::asString)).containsExactly("customer");
    }

    @Test
    void aMintedSessionCannotReachAdminRoutes() {
        TestBrowser browser = browser();
        mint(browser);

        assertThat(browser.get("/api/admin/products").statusCode()).isEqualTo(403);
    }

    @Test
    void theMintedSessionLivesForTheConfiguredTimeoutAndCarriesTheLoginStamps() {
        TestBrowser browser = browser();
        mint(browser);

        var session = sessions.findById(sessionIdOf(browser));
        assertThat(session.getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(session.getAttributeNames())
                .contains(
                        SessionAttributes.AUTHENTICATED_AT,
                        SessionAttributes.LAST_REVALIDATION_ATTEMPT,
                        SessionAttributes.LAST_REVALIDATION_SUCCESS);
    }

    @Test
    void csrfIsStillEnforcedOnTheMintedSession() {
        TestBrowser browser = browser();
        mint(browser);
        AdminClient client = new AdminClient(browser);

        HttpResponse<String> withoutToken = client.postWithoutCsrf("/api/checkout", "{}");
        HttpResponse<String> cartWrite = client.put("/api/cart/lines/" + LAMP, "{\"quantity\":1}");

        assertThat(withoutToken.statusCode()).isEqualTo(403);
        assertThat(cartWrite.statusCode()).isEqualTo(200);
    }

    @Test
    void aMissingOrWrongTokenIsRefusedWithoutEchoingIt(CapturedOutput output) {
        TestBrowser browser = browser();
        String wrong = "wrong-token-value-that-must-not-be-logged";

        HttpResponse<String> missing = browser.post(PATH, null);
        HttpResponse<String> mismatch = browser.post(PATH, null, "Shop-Loadtest-Token", wrong);

        assertThat(missing.statusCode()).isEqualTo(401);
        assertThat(mismatch.statusCode()).isEqualTo(401);
        assertThat(mismatch.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        assertThat(browser.cookie(TestBrowser.SESSION_COOKIE)).isNull();
        assertThat(output.getAll()).doesNotContain(wrong).doesNotContain(TOKEN).contains("missing or wrong token");
    }

    @Test
    void onlyAnExactPostMints() {
        TestBrowser browser = browser();

        HttpResponse<String> get = browser.get(PATH, "Shop-Loadtest-Token", TOKEN);
        HttpResponse<String> slash = browser.post(PATH + "/", null, "Shop-Loadtest-Token", TOKEN);

        assertThat(get.statusCode()).isNotEqualTo(201);
        assertThat(slash.statusCode()).isNotEqualTo(201);
        assertThat(browser.cookie(TestBrowser.SESSION_COOKIE)).isNull();
    }

    @Test
    void mintingWithAnExistingSessionIssuesANewIdAndEndsTheOldSession() {
        TestBrowser browser = signedInAs(Identity.customer());
        String oldId = sessionIdOf(browser);

        HttpResponse<String> response = mint(browser);

        assertThat(response.statusCode()).isEqualTo(201);
        String newId = sessionIdOf(browser);
        assertThat(newId).isNotNull().isNotEqualTo(oldId);
        assertThat(sessions.findById(oldId)).isNull();
        assertThat(sessions.findById(newId)).isNotNull();
    }

    @Test
    void aMintedSessionEndsAtTheFirstRevalidationBecauseNoAuthorizedClientIsStored() {
        TestBrowser browser = browser();
        mint(browser);
        clock.advance(Duration.ofMinutes(6));

        JsonNode me = AdminClient.json(browser.get("/api/me"));

        assertThat(me.get("authenticated").asBoolean()).isFalse();
    }

    @Test
    void aMintedSessionStaysWithinItsShareOfTheValkeyBudget() {
        TestBrowser browser = browser();
        mint(browser);
        String id = sessionIdOf(browser);
        long bytes = 0;
        try (var connection = redisConnections.getConnection();
                Cursor<byte[]> keys = connection
                        .keyCommands()
                        .scan(ScanOptions.scanOptions()
                                .match("*" + id + "*")
                                .count(1000)
                                .build())) {
            while (keys.hasNext()) {
                byte[] key = keys.next();
                bytes += key.length;
                var hash = connection.hashCommands().hGetAll(key);
                if (hash != null) {
                    for (var entry : hash.entrySet()) {
                        bytes += entry.getKey().length + entry.getValue().length;
                    }
                }
            }
        }
        System.out.println("MINTED_SESSION_BYTES=" + bytes);

        assertThat(bytes).isPositive().isLessThan(4_000);
    }

    @Test
    void theHandlerClassesAreNotPartOfTheOpenApiDocument() {
        assertThat(browser().get("/v3/api-docs").body()).doesNotContain("loadtest");
    }
}
