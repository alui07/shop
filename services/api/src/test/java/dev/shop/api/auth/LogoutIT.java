package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AuthIT;
import dev.shop.api.support.Identity;
import dev.shop.api.support.StubIdentityProvider;
import dev.shop.api.support.TestBrowser;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LogoutIT extends AuthIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void logoutWithoutACsrfTokenIsForbiddenAndKeepsTheSession() throws Exception {
        TestBrowser browser = signedInAs(Identity.customer());
        String sessionId = sessionIdOf(browser);

        HttpResponse<String> response = browser.post("/logout", null);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        assertThat(JSON.readTree(response.body()).get("detail").asString()).isEqualTo("CSRF token missing or invalid");
        assertThat(sessions.findById(sessionId)).isNotNull();
    }

    @Test
    void logoutWithAForgedCsrfTokenIsForbidden() {
        TestBrowser browser = signedInAs(Identity.customer());

        HttpResponse<String> response = browser.post("/logout", null, "X-XSRF-TOKEN", "forged");

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void logoutReturnsTheKeycloakLogoutUrlAsJsonAndEndsTheSession() throws Exception {
        Identity identity = Identity.customer();
        TestBrowser browser = signedInAs(identity);
        browser.get("/api/me");
        String sessionId = sessionIdOf(browser);

        HttpResponse<String> response = browser.postWithCsrf("/logout", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        String redirectUrl = JSON.readTree(response.body()).get("redirectUrl").asString();
        assertThat(redirectUrl).startsWith("https://idp.example.test/realms/shop/protocol/openid-connect/logout?");
        Map<String, String> query = TestBrowser.query(redirectUrl);
        assertThat(query)
                .containsEntry("post_logout_redirect_uri", PUBLIC_ORIGIN + "/")
                .containsEntry("client_id", "shop-api");
        assertThat(query.get("id_token_hint")).isNotBlank().contains(".");
        assertThat(sessions.findById(sessionId)).isNull();
        assertThat(redis.opsForSet().members("shop:oidc:sid:" + identity.sessionId()))
                .isEmpty();
        assertThat(redis.hasKey("shop:oidc:session:" + sessionId)).isFalse();
        assertThat(browser.cookie(TestBrowser.SESSION_COOKIE)).isNull();
        assertThat(browser.get("/api/me").body()).contains("\"authenticated\":false");
    }

    @Test
    void anAnonymousLogoutJustPointsHome() throws Exception {
        TestBrowser browser = browser();
        browser.get("/api/me");

        HttpResponse<String> response = browser.postWithCsrf("/logout", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).get("redirectUrl").asString()).isEqualTo(PUBLIC_ORIGIN + "/");
    }

    @Test
    void spoofedHostAndForwardedHeadersNeverReachAnyRedirect() throws Exception {
        TestBrowser browser = browser();
        String[] spoof = {
            "X-Forwarded-Host",
            "evil.example.test",
            "X-Forwarded-Proto",
            "https",
            "X-Forwarded-Port",
            "4443",
            "Forwarded",
            "host=evil.example.test;proto=https"
        };

        HttpResponse<String> authorize = browser.get("/oauth2/authorization/keycloak", spoof);
        Map<String, String> query = TestBrowser.query(TestBrowser.location(authorize));
        assertThat(TestBrowser.location(authorize)).doesNotContain("evil.example.test");
        assertThat(query.get("redirect_uri")).isEqualTo(PUBLIC_ORIGIN + "/login/oauth2/code/keycloak");

        String code = idp.issueCode(new StubIdentityProvider.Login(
                Identity.customer(),
                query.get("nonce"),
                query.get("code_challenge"),
                claims -> {},
                StubIdentityProvider.Signing.VALID));
        HttpResponse<String> callback =
                browser.get("/login/oauth2/code/keycloak?code=" + code + "&state=" + query.get("state"), spoof);
        assertThat(TestBrowser.location(callback)).isEqualTo(PUBLIC_ORIGIN + "/");

        browser.get("/api/me");
        HttpResponse<String> logout = browser.postWithCsrf("/logout", null, spoof);
        assertThat(logout.body()).doesNotContain("evil.example.test");
        assertThat(logout.headers().firstValue("Location")).isEmpty();
    }
}
