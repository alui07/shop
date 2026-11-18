package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.JWTClaimsSet;
import dev.shop.api.support.AuthIT;
import dev.shop.api.support.Identity;
import dev.shop.api.support.StubIdentityProvider.Signing;
import dev.shop.api.support.TestBrowser;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BackChannelLogoutIT extends AuthIT {

    private static final String ENDPOINT = "/logout/connect/back-channel/keycloak";

    @Test
    void aLogoutTokenWithTheSidEndsThatSessionOnly() {
        Identity customer = Identity.customer();
        TestBrowser first = signedInAs(customer);
        TestBrowser other = signedInAs(Identity.admin());
        String firstSession = sessionIdOf(first);

        HttpResponse<String> response = backChannelLogout(
                Signing.VALID, claims -> claims.subject("customer-1").claim("sid", customer.sessionId()));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(sessions.findById(firstSession)).isNull();
        assertThat(first.get("/api/me").body()).contains("\"authenticated\":false");
        assertThat(other.get("/api/me").body()).contains("\"authenticated\":true");
        assertThat(redis.hasKey("shop:oidc:session:" + firstSession)).isFalse();
    }

    @Test
    void itIsThePostRotationSessionThatDies() {
        Identity customer = Identity.customer();
        TestBrowser browser = browser();
        browser.get("/oauth2/authorization/keycloak");
        browser.login(customer);

        backChannelLogout(Signing.VALID, claims -> claims.claim("sid", customer.sessionId()));

        assertThat(browser.get("/api/me").body()).contains("\"authenticated\":false");
    }

    @Test
    void aLogoutTokenWithOnlyTheSubjectEndsEverySessionOfThatUser() {
        TestBrowser laptop = signedInAs(Identity.customer());
        TestBrowser phone = signedInAs(Identity.customer());
        TestBrowser other = signedInAs(Identity.admin());

        HttpResponse<String> response = backChannelLogout(Signing.VALID, claims -> claims.subject("customer-1"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(laptop.get("/api/me").body()).contains("\"authenticated\":false");
        assertThat(phone.get("/api/me").body()).contains("\"authenticated\":false");
        assertThat(other.get("/api/me").body()).contains("\"authenticated\":true");
    }

    @Test
    void twoSessionsOfTheSameKeycloakSessionBothEnd() {
        Identity identity = Identity.customer();
        TestBrowser first = browser();
        TestBrowser second = browser();
        first.login(identity);
        second.login(identity);

        backChannelLogout(Signing.VALID, claims -> claims.claim("sid", identity.sessionId()));

        assertThat(first.get("/api/me").body()).contains("\"authenticated\":false");
        assertThat(second.get("/api/me").body()).contains("\"authenticated\":false");
    }

    @Test
    void aSidBelongingToAnotherUserLeavesTheSessionAlive() {
        TestBrowser victim = signedInAs(Identity.customer());
        Identity attacker = Identity.admin();
        TestBrowser attackerBrowser = signedInAs(attacker);

        HttpResponse<String> response = backChannelLogout(
                Signing.VALID,
                claims -> claims.subject("admin-1")
                        .claim("sid", Identity.customer().sessionId()));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(victim.get("/api/me").body()).contains("\"authenticated\":true");
        assertThat(attackerBrowser.get("/api/me").body()).contains("\"authenticated\":true");
    }

    @Test
    void aMatchingSidWithAForeignSubjectLeavesTheSessionAlive() {
        Identity customer = Identity.customer();
        TestBrowser browser = signedInAs(customer);

        backChannelLogout(Signing.VALID, claims -> claims.subject("admin-1").claim("sid", customer.sessionId()));

        assertThat(browser.get("/api/me").body()).contains("\"authenticated\":true");
    }

    @Test
    void aStaleRegistryEntryWithoutASessionRecordIsSkipped() {
        redis.opsForSet().add("shop:oidc:sid:ghost", "no-such-session");

        HttpResponse<String> response = backChannelLogout(Signing.VALID, claims -> claims.claim("sid", "ghost"));

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void aRecordFromAnotherIssuerIsNeverMatched() {
        Identity customer = Identity.customer();
        TestBrowser browser = signedInAs(customer);
        redis.opsForHash()
                .put("shop:oidc:session:" + sessionIdOf(browser), "iss", "https://other.example.test/realms/shop");

        backChannelLogout(Signing.VALID, claims -> claims.subject("customer-1").claim("sid", customer.sessionId()));

        assertThat(browser.get("/api/me").body()).contains("\"authenticated\":true");
    }

    @Test
    void aLogoutTokenForAnUnknownSessionIsAccepted() {
        HttpResponse<String> response = backChannelLogout(Signing.VALID, claims -> claims.claim("sid", "nobody"));

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidLogoutTokens")
    void anInvalidLogoutTokenLeavesTheSessionAlive(String name, Signing signing, Consumer<JWTClaimsSet.Builder> tweak) {
        Identity customer = Identity.customer();
        TestBrowser browser = signedInAs(customer);

        HttpResponse<String> response = backChannelLogout(signing, claims -> {
            claims.subject("customer-1").claim("sid", customer.sessionId());
            tweak.accept(claims);
        });

        assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(browser.get("/api/me").body()).contains("\"authenticated\":true");
    }

    static Stream<Arguments> invalidLogoutTokens() {
        return Stream.of(
                Arguments.of(
                        "nonce present", Signing.VALID, (Consumer<JWTClaimsSet.Builder>) c -> c.claim("nonce", "n")),
                Arguments.of("no events", Signing.VALID, (Consumer<JWTClaimsSet.Builder>) c -> c.claim("events", null)),
                Arguments.of("wrong event", Signing.VALID, (Consumer<JWTClaimsSet.Builder>)
                        c -> c.claim("events", Map.of("other", Map.of()))),
                Arguments.of("wrong issuer", Signing.VALID, (Consumer<JWTClaimsSet.Builder>)
                        c -> c.issuer("https://evil.example.test/realms/shop")),
                Arguments.of("wrong audience", Signing.VALID, (Consumer<JWTClaimsSet.Builder>)
                        c -> c.audience("another-client")),
                Arguments.of("unknown signing key", Signing.UNKNOWN_KEY, (Consumer<JWTClaimsSet.Builder>) c -> {}),
                Arguments.of("alg none", Signing.UNSIGNED, (Consumer<JWTClaimsSet.Builder>) c -> {}),
                Arguments.of("neither sid nor sub", Signing.VALID, (Consumer<JWTClaimsSet.Builder>)
                        c -> c.subject(null).claim("sid", null)));
    }

    @Test
    void aBodyWithoutALogoutTokenIsRejected() {
        TestBrowser browser = browser();

        HttpResponse<String> response =
                browser.post(ENDPOINT, "other=1", "Content-Type", "application/x-www-form-urlencoded");

        assertThat(response.statusCode()).isEqualTo(400);
    }

    private HttpResponse<String> backChannelLogout(Signing signing, Consumer<JWTClaimsSet.Builder> tweak) {
        String token = idp.logoutToken(signing, tweak);
        return browser()
                .post(
                        ENDPOINT,
                        "logout_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8),
                        "Content-Type",
                        "application/x-www-form-urlencoded");
    }
}
