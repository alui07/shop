package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AdminClient;
import dev.shop.api.support.AuthIT;
import dev.shop.api.support.CatalogFixtures;
import dev.shop.api.support.Identity;
import dev.shop.api.support.StubIdentityProvider.RefreshMode;
import dev.shop.api.support.TestBrowser;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

class RevalidationIT extends AuthIT {

    @Autowired
    private JdbcClient jdbc;

    private long auditMark;

    private static final Duration PAST_REVALIDATION = Duration.ofMinutes(6);

    @Test
    void keycloakIsNotAskedBeforeTheRevalidationInterval() {
        TestBrowser browser = signedInAs(Identity.admin());

        clock.advance(Duration.ofMinutes(4));

        assertThat(browser.get("/api/admin/products").statusCode()).isEqualTo(200);
        assertThat(idp.refreshCount()).isZero();
    }

    @Test
    void aDemotionInKeycloakTakesEffectOnTheNextRequestAfterTheInterval() {
        Identity admin = Identity.admin();
        TestBrowser browser = signedInAs(admin);
        idp.updateIdentity(admin.withRoles(List.of("customer")));

        clock.advance(PAST_REVALIDATION);
        HttpResponse<String> response = browser.get("/api/admin/products");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(idp.refreshCount()).isEqualTo(1);
        assertThat(browser.get("/api/me").body()).contains("\"roles\":[\"customer\"]");
    }

    @Test
    void theRefreshedSessionKeepsWorkingAndIsAskedAgainAfterAnotherInterval() {
        TestBrowser browser = signedInAs(Identity.admin());

        clock.advance(PAST_REVALIDATION);
        assertThat(browser.get("/api/admin/products").statusCode()).isEqualTo(200);
        assertThat(browser.get("/api/admin/products").statusCode()).isEqualTo(200);
        assertThat(idp.refreshCount()).isEqualTo(1);

        clock.advance(PAST_REVALIDATION);
        assertThat(browser.get("/api/admin/products").statusCode()).isEqualTo(200);
        assertThat(idp.refreshCount()).isEqualTo(2);
    }

    @Test
    void aRevokedKeycloakSessionEndsTheAppSessionOnTheSameRequest() {
        TestBrowser browser = signedInAs(Identity.admin());
        String sessionId = sessionIdOf(browser);
        idp.setRefreshMode(RefreshMode.INVALID_GRANT);

        clock.advance(PAST_REVALIDATION);
        HttpResponse<String> response = browser.get("/api/admin/products");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(sessions.findById(sessionId)).isNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = RefreshMode.class,
            names = {"ID_TOKEN_FOR_ANOTHER_USER", "FORGED_ID_TOKEN"})
    void aRefreshThatReturnsAnUntrustworthyIdTokenEndsTheSession(RefreshMode mode) {
        TestBrowser browser = signedInAs(Identity.admin());
        idp.setRefreshMode(mode);

        clock.advance(PAST_REVALIDATION);

        assertThat(browser.get("/api/admin/products").statusCode()).isEqualTo(401);
    }

    @Test
    void aRefreshWithoutAnIdTokenCountsAsKeycloakBeingUnavailable() {
        TestBrowser browser = signedInAs(Identity.admin());
        idp.setRefreshMode(RefreshMode.NO_ID_TOKEN);

        clock.advance(PAST_REVALIDATION);
        assertThat(browser.get("/api/admin/products").statusCode()).isEqualTo(200);
        clock.advance(PAST_REVALIDATION);
        assertThat(browser.get("/api/admin/products").statusCode()).isEqualTo(200);
        clock.advance(PAST_REVALIDATION);
        assertThat(browser.get("/api/admin/products").statusCode()).isEqualTo(401);
        assertThat(idp.refreshCount()).isEqualTo(3);
    }

    @Test
    void aRequestThatLoadedTheSessionBeforeTheRefreshWasCommittedDoesNotReuseTheRotatedToken() {
        TestBrowser browser = signedInAs(Identity.admin());
        String sessionId = sessionIdOf(browser);
        Map<String, Object> beforeRefresh = attributesOf(sessionId);

        clock.advance(PAST_REVALIDATION);
        assertThat(browser.get("/api/me").statusCode()).isEqualTo(200);
        assertThat(idp.refreshCount()).isEqualTo(1);
        restoreAttributes(sessionId, beforeRefresh);

        HttpResponse<String> late = browser.get("/api/admin/products");

        assertThat(late.statusCode()).isEqualTo(200);
        assertThat(idp.refreshCount()).isEqualTo(1);
        assertThat(sessions.findById(sessionId)).isNotNull();
    }

    private Map<String, Object> attributesOf(String sessionId) {
        Session session = sessions.findById(sessionId);
        Map<String, Object> attributes = new HashMap<>();
        session.getAttributeNames().forEach(name -> attributes.put(name, session.getAttribute(name)));
        return attributes;
    }

    private void restoreAttributes(String sessionId, Map<String, Object> attributes) {
        restore(sessions, sessionId, attributes);
    }

    private static <S extends Session> void restore(
            SessionRepository<S> repository, String sessionId, Map<String, Object> attributes) {
        S session = repository.findById(sessionId);
        Set.copyOf(session.getAttributeNames()).stream()
                .filter(name -> !attributes.containsKey(name))
                .forEach(session::removeAttribute);
        attributes.forEach(session::setAttribute);
        repository.save(session);
    }

    @Test
    void keycloakBeingDownKeepsTheSessionUntilTheGraceEnds() {
        TestBrowser browser = signedInAs(Identity.admin());
        idp.setRefreshMode(RefreshMode.SERVER_ERROR);

        clock.advance(PAST_REVALIDATION);
        assertThat(browser.get("/api/admin/products").statusCode()).isEqualTo(200);
        clock.advance(PAST_REVALIDATION);
        assertThat(browser.get("/api/admin/products").statusCode()).isEqualTo(200);
        clock.advance(PAST_REVALIDATION);
        assertThat(browser.get("/api/admin/products").statusCode()).isEqualTo(401);
        assertThat(idp.refreshCount()).isEqualTo(3);
    }

    @Test
    void keycloakBeingDownIsNotAskedOnEveryRequest() {
        TestBrowser browser = signedInAs(Identity.admin());
        idp.setRefreshMode(RefreshMode.SERVER_ERROR);

        clock.advance(PAST_REVALIDATION);
        browser.get("/api/me");
        browser.get("/api/me");
        browser.get("/api/me");

        assertThat(idp.refreshCount()).isEqualTo(1);
    }

    @Test
    void twoConcurrentRequestsTriggerOneRefresh() {
        Identity admin = Identity.admin();
        TestBrowser first = signedInAs(admin);
        TestBrowser second = browser();
        second.setCookie(TestBrowser.SESSION_COOKIE, first.cookie(TestBrowser.SESSION_COOKIE));
        idp.setRefreshDelayMillis(1500);

        clock.advance(PAST_REVALIDATION);
        CompletableFuture<HttpResponse<String>> a = CompletableFuture.supplyAsync(() -> first.get("/api/me"));
        CompletableFuture<HttpResponse<String>> b = CompletableFuture.supplyAsync(() -> second.get("/api/me"));

        assertThat(a.join().statusCode()).isEqualTo(200);
        assertThat(b.join().statusCode()).isEqualTo(200);
        assertThat(idp.refreshCount()).isEqualTo(1);
    }

    @Test
    void aDemotedAdminCannotWriteWhileKeycloakIsDownButStillReadsWithinTheGrace() {
        Identity admin = Identity.admin();
        AdminClient client = new AdminClient(signedInAs(admin));
        long id = oneProduct();
        idp.updateIdentity(admin.withRoles(List.of("customer")));
        idp.setRefreshMode(RefreshMode.SERVER_ERROR);

        clock.advance(PAST_REVALIDATION);
        HttpResponse<String> write = client.put("/api/admin/products/" + id, productUpdate(0));

        assertThat(write.statusCode()).isEqualTo(503);
        assertThat(write.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        assertThat(write.body()).contains("Your access cannot be confirmed right now");
        assertThat(write.headers().firstValue("Retry-After")).contains("300");
        assertThat(client.get("/api/admin/products").statusCode()).isEqualTo(200);
        assertThat(auditRowsAboveTheMark()).isZero();
    }

    @Test
    void aPercentEncodedAdminPathDoesNotSkipTheOverdueConfirmationCheck() {
        AdminClient client = new AdminClient(signedInAs(Identity.admin()));
        long id = oneProduct();
        idp.setRefreshMode(RefreshMode.SERVER_ERROR);
        clock.advance(PAST_REVALIDATION);

        HttpResponse<String> write = client.put("/api/%61dmin/products/" + id, productUpdate(0));

        assertThat(write.statusCode()).isEqualTo(503);
        assertThat(auditRowsAboveTheMark()).isZero();
    }

    @Test
    void afterKeycloakRecoversTheSameWriteSucceedsForAnAdminWhoKeepsTheRole() {
        AdminClient client = new AdminClient(signedInAs(Identity.admin()));
        long id = oneProduct();
        idp.setRefreshMode(RefreshMode.SERVER_ERROR);
        clock.advance(PAST_REVALIDATION);
        assertThat(client.put("/api/admin/products/" + id, productUpdate(0)).statusCode())
                .isEqualTo(503);

        idp.setRefreshMode(RefreshMode.OK);
        clock.advance(PAST_REVALIDATION);

        HttpResponse<String> recovered = client.put("/api/admin/products/" + id, productUpdate(0));
        assertThat(recovered.statusCode()).as(recovered.body()).isEqualTo(200);
        assertThat(auditRowsAboveTheMark()).isEqualTo(1);
    }

    @Test
    void aDemotionAppliedAtRecoveryTurnsTheWriteIntoA403() {
        Identity admin = Identity.admin();
        AdminClient client = new AdminClient(signedInAs(admin));
        long id = oneProduct();
        idp.updateIdentity(admin.withRoles(List.of("customer")));

        clock.advance(PAST_REVALIDATION);

        assertThat(client.put("/api/admin/products/" + id, productUpdate(0)).statusCode())
                .isEqualTo(403);
    }

    private long oneProduct() {
        CatalogFixtures.clear(jdbc);
        CatalogFixtures.addCategory(jdbc, "office", "Office");
        CatalogFixtures.addProduct(jdbc, "walnut-desk", "Walnut Desk", "Solid", 49900, "office", true);
        auditMark = CatalogFixtures.auditHighWaterMark(jdbc);
        return CatalogFixtures.idOf(jdbc, "walnut-desk");
    }

    private static String productUpdate(long version) {
        return "{\"name\":\"Renamed\",\"description\":\"Solid\",\"priceMinor\":49900,\"categorySlug\":\"office\","
                + "\"active\":true,\"version\":" + version + "}";
    }

    private long auditRowsAboveTheMark() {
        return jdbc.sql("SELECT count(*) FROM admin_audit_event WHERE id > :mark")
                .param("mark", auditMark)
                .query(Long.class)
                .single();
    }
}
