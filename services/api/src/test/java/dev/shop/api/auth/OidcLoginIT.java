package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.JWTClaimsSet;
import dev.shop.api.support.AuthIT;
import dev.shop.api.support.Identity;
import dev.shop.api.support.StubIdentityProvider;
import dev.shop.api.support.StubIdentityProvider.Signing;
import dev.shop.api.support.TestBrowser;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OidcLoginIT extends AuthIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void theAuthorizationRedirectUsesPkceStateNonceAndTheExactRedirectUri() {
        TestBrowser browser = browser();

        HttpResponse<String> response = browser.get("/oauth2/authorization/keycloak");

        assertThat(response.statusCode()).isEqualTo(302);
        String location = TestBrowser.location(response);
        assertThat(location).startsWith("https://idp.example.test/realms/shop/protocol/openid-connect/auth?");
        Map<String, String> query = TestBrowser.query(location);
        assertThat(query)
                .containsEntry("response_type", "code")
                .containsEntry("client_id", "shop-api")
                .containsEntry("scope", "openid profile email")
                .containsEntry("redirect_uri", PUBLIC_ORIGIN + "/login/oauth2/code/keycloak")
                .containsEntry("code_challenge_method", "S256")
                .containsKeys("state", "nonce", "code_challenge");
    }

    @Test
    void theSessionBehindTheLoginRedirectIsShortLived() {
        TestBrowser browser = browser();
        browser.get("/oauth2/authorization/keycloak");

        assertThat(sessions.findById(sessionIdOf(browser)).getMaxInactiveInterval())
                .isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void aSuccessfulLoginRotatesTheSessionAndSetsAHardenedCookie() {
        TestBrowser browser = browser();
        browser.get("/oauth2/authorization/keycloak");
        String sessionBeforeLogin = sessionIdOf(browser);
        Map<String, String> query = browser.startLogin();
        String sessionAtLoginStart = sessionIdOf(browser);

        HttpResponse<String> callback = browser.login(Identity.customer());

        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(TestBrowser.location(callback)).isEqualTo(PUBLIC_ORIGIN + "/");
        String sessionCookie = callback.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith("__Host-SESSION="))
                .findFirst()
                .orElseThrow();
        assertThat(sessionCookie)
                .contains("Path=/", "Secure", "HttpOnly", "SameSite=Lax")
                .doesNotContain("Domain");
        assertThat(sessionIdOf(browser)).isNotEqualTo(sessionBeforeLogin).isNotEqualTo(sessionAtLoginStart);
        assertThat(query).containsKey("state");
        assertThat(idp.lastCodeVerifier()).isNotBlank();
        assertThat(sessions.findById(sessionIdOf(browser)).getMaxInactiveInterval())
                .isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void theSignedInUserIsDescribedWithoutAnyToken() throws Exception {
        TestBrowser browser = signedInAs(Identity.admin());

        HttpResponse<String> response = browser.get("/api/me");

        JsonNode me = JSON.readTree(response.body());
        assertThat(me.get("authenticated").asBoolean()).isTrue();
        assertThat(me.get("subject").asString()).isEqualTo("admin-1");
        assertThat(me.get("name").asString()).isEqualTo("Alex Admin");
        assertThat(me.get("email").asString()).isEqualTo("admin@example.test");
        assertThat(me.get("roles").valueStream().map(JsonNode::asString)).containsExactly("admin", "customer");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(response.body()).doesNotContain("token", "Token");
    }

    @Test
    void anonymousCallersAreToldTheyAreNotSignedIn() throws Exception {
        HttpResponse<String> response = browser().get("/api/me");

        JsonNode me = JSON.readTree(response.body());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(me.get("authenticated").asBoolean()).isFalse();
        assertThat(me.get("subject").isNull()).isTrue();
        assertThat(me.get("roles")).isEmpty();
    }

    @Test
    void rolesOutsideTheAllowlistAreDropped() throws Exception {
        Identity identity = Identity.customer().withRoles(List.of("customer", "superuser", "ROLE_ADMIN", "admin "));

        TestBrowser browser = signedInAs(identity);

        JsonNode me = JSON.readTree(browser.get("/api/me").body());
        assertThat(me.get("roles").valueStream().map(JsonNode::asString)).containsExactly("customer");
        assertThat(browser.get("/api/admin/products").statusCode()).isEqualTo(403);
    }

    @Test
    void aMissingRolesClaimMeansNoRoles() throws Exception {
        TestBrowser browser = browser();
        browser.login(Identity.customer(), claims -> claims.claim("roles", "not-a-list"));

        JsonNode me = JSON.readTree(browser.get("/api/me").body());

        assertThat(me.get("authenticated").asBoolean()).isTrue();
        assertThat(me.get("roles")).isEmpty();
    }

    @Test
    void theDisplayNameFallsBackToThePreferredUsername() throws Exception {
        TestBrowser browser = browser();
        browser.login(Identity.customer(), claims -> claims.claim("name", null));

        JsonNode me = JSON.readTree(browser.get("/api/me").body());

        assertThat(me.get("name").asString()).isEqualTo("customer@example.test");
    }

    @Test
    void aLoginWithoutASidIsRegisteredBySubjectOnlyAndUnregisteredOnLogout() {
        Identity identity = Identity.customer();
        TestBrowser browser = browser();
        browser.login(identity, claims -> claims.claim("sid", null));
        String sessionId = sessionIdOf(browser);
        assertThat(redis.opsForSet().members("shop:oidc:sub:customer-1")).contains(sessionId);
        assertThat(redis.opsForHash().entries("shop:oidc:session:" + sessionId)).containsEntry("sid", "");

        browser.get("/api/me");
        browser.postWithCsrf("/logout", null);

        assertThat(redis.hasKey("shop:oidc:session:" + sessionId)).isFalse();
        assertThat(redis.opsForSet().members("shop:oidc:sub:customer-1")).doesNotContain(sessionId);
    }

    @Test
    void theRegistryHoldsTheSessionIdAfterRotation() {
        Identity identity = Identity.customer();
        TestBrowser browser = browser();
        browser.get("/oauth2/authorization/keycloak");
        String sessionBeforeLogin = sessionIdOf(browser);

        browser.login(identity);

        String sessionAfterLogin = sessionIdOf(browser);
        assertThat(redis.opsForSet().members("shop:oidc:sid:" + identity.sessionId()))
                .containsExactly(sessionAfterLogin)
                .doesNotContain(sessionBeforeLogin);
        assertThat(redis.opsForHash().entries("shop:oidc:session:" + sessionAfterLogin))
                .containsEntry("sub", "customer-1")
                .containsEntry("sid", identity.sessionId())
                .containsEntry("iss", StubIdentityProvider.ISSUER)
                .hasSize(3);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badIdTokens")
    void aBadIdTokenFailsTheLoginAndLeavesNoSession(
            String name, Consumer<JWTClaimsSet.Builder> tweak, Signing signing, String nonce) {
        TestBrowser browser = browser();

        HttpResponse<String> callback =
                browser.login(new StubIdentityProvider.Login(Identity.customer(), nonce, null, tweak, signing));

        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(TestBrowser.location(callback)).isEqualTo(PUBLIC_ORIGIN + "/?login=failed");
        assertThat(browser.get("/api/me").body()).contains("\"authenticated\":false");
        assertThat(browser.get("/api/admin/products").statusCode()).isEqualTo(401);
    }

    static Stream<Arguments> badIdTokens() {
        Consumer<JWTClaimsSet.Builder> none = claims -> {};
        return Stream.of(
                Arguments.of(
                        "wrong issuer",
                        (Consumer<JWTClaimsSet.Builder>) c -> c.issuer("https://evil.example.test/realms/shop"),
                        Signing.VALID,
                        null),
                Arguments.of(
                        "wrong audience",
                        (Consumer<JWTClaimsSet.Builder>) c -> c.audience("another-client"),
                        Signing.VALID,
                        null),
                Arguments.of(
                        "expired",
                        (Consumer<JWTClaimsSet.Builder>)
                                c -> c.expirationTime(Date.from(Instant.now().minusSeconds(600))),
                        Signing.VALID,
                        null),
                Arguments.of("alg none", none, Signing.UNSIGNED, null),
                Arguments.of("unknown signing key", none, Signing.UNKNOWN_KEY, null),
                Arguments.of("wrong nonce", none, Signing.VALID, "not-the-nonce"));
    }

    @Test
    void aCallbackWithAnUnknownStateFailsTheLogin() {
        TestBrowser browser = browser();
        browser.get("/oauth2/authorization/keycloak");

        HttpResponse<String> callback = browser.get("/login/oauth2/code/keycloak?code=whatever&state=forged");

        assertThat(TestBrowser.location(callback)).isEqualTo(PUBLIC_ORIGIN + "/?login=failed");
    }

    @Test
    void aCodeTheIdentityProviderRejectsFailsTheLogin() {
        TestBrowser browser = browser();
        Map<String, String> query = browser.startLogin();

        HttpResponse<String> callback =
                browser.get("/login/oauth2/code/keycloak?code=unknown&state=" + query.get("state"));

        assertThat(TestBrowser.location(callback)).isEqualTo(PUBLIC_ORIGIN + "/?login=failed");
    }

    @Test
    void anAnonymousSessionIsNeverCreatedByPublicRequests() {
        TestBrowser browser = browser();

        for (String path : List.of("/api/status", "/api/products", "/api/me", "/api/unknown", "/api/admin/products")) {
            HttpResponse<String> response = browser.get(path);
            assertThat(response.headers().allValues("Set-Cookie"))
                    .as(path)
                    .noneMatch(header -> header.startsWith("__Host-SESSION="));
        }
        assertThat(browser.cookie(TestBrowser.SESSION_COOKIE)).isNull();
    }
}
