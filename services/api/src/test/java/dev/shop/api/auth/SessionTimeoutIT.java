package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AuthIT;
import dev.shop.api.support.Identity;
import dev.shop.api.support.TestBrowser;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.session.autoconfigure.SessionProperties;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

class SessionTimeoutIT extends AuthIT {

    @Test
    void theIdleTimeoutIsThirtyMinutesInValkey() {
        TestBrowser browser = signedInAs(Identity.customer());

        Long seconds = redis.getExpire("spring:session:sessions:" + sessionIdOf(browser));

        assertThat(seconds).isBetween(1790L, 1800L);
    }

    @Test
    void aSessionLivesUntilTheAbsoluteTimeoutAndNoLonger() {
        TestBrowser browser = signedInAs(Identity.admin());
        String sessionId = sessionIdOf(browser);

        clock.advance(Duration.ofHours(7).plusMinutes(55));
        assertThat(browser.get("/api/admin/products").statusCode()).isEqualTo(200);

        clock.advance(Duration.ofMinutes(10));
        HttpResponse<String> expiring = browser.get("/api/admin/products");

        assertThat(expiring.statusCode()).isEqualTo(401);
        assertThat(sessions.findById(sessionId)).isNull();
        assertThat(redis.hasKey("shop:oidc:session:" + sessionId)).isFalse();
        assertThat(browser.get("/api/me").body()).contains("\"authenticated\":false");
    }

    @Test
    void theExpiringRequestReplacesTheCsrfToken() {
        TestBrowser browser = signedInAs(Identity.admin());
        browser.get("/api/me");
        String tokenBefore = browser.cookie(TestBrowser.CSRF_COOKIE);
        clock.advance(Duration.ofHours(9));

        HttpResponse<String> expiring = browser.get("/api/admin/products");

        assertThat(expiring.headers().allValues("Set-Cookie"))
                .anyMatch(header -> header.startsWith("__Host-XSRF-TOKEN=;") && header.contains("1970"));
        assertThat(browser.cookie(TestBrowser.CSRF_COOKIE)).isNotNull().isNotEqualTo(tokenBefore);
    }

    @Test
    void aSignedInSessionThatWasNeverStampedCountsAsExpired() {
        TestBrowser unstamped = browserWithSession(session -> session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(adminPrincipal())));
        TestBrowser stamped = browserWithSession(session -> {
            session.setAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                    new SecurityContextImpl(adminPrincipal()));
            session.setAttribute(SessionAttributes.AUTHENTICATED_AT, clock.instant());
        });

        assertThat(unstamped.get("/api/admin/products").statusCode()).isEqualTo(401);
        assertThat(stamped.get("/api/admin/products").statusCode()).isEqualTo(200);
    }

    @Autowired
    private ShopAuthProperties authProperties;

    @Autowired
    private SessionProperties sessionProperties;

    @Test
    void theSpringSessionTimeoutIsDerivedFromTheAuthIdleTimeout() {
        assertThat(sessionProperties.getTimeout()).isEqualTo(authProperties.idleSessionTimeout());
    }
}
