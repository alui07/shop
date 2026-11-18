package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AuthIT;
import dev.shop.api.support.Identity;
import dev.shop.api.support.TestBrowser;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class CsrfIT extends AuthIT {

    @Test
    void theCsrfCookieIsReadableByScriptsAndLockedToTheHost() {
        HttpResponse<String> response = browser().get("/api/me");

        String cookie = response.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith("__Host-XSRF-TOKEN="))
                .findFirst()
                .orElseThrow();
        assertThat(cookie).contains("Path=/", "Secure", "SameSite=Strict").doesNotContain("HttpOnly", "Domain");
    }

    @Test
    void theLoginResponseCarriesAFreshCsrfTokenThatWorks() {
        TestBrowser browser = browser();
        browser.get("/api/me");
        String tokenBeforeLogin = browser.cookie(TestBrowser.CSRF_COOKIE);

        HttpResponse<String> callback = browser.login(Identity.customer());

        assertThat(setCookies(callback)).anyMatch(header -> header.startsWith("__Host-XSRF-TOKEN="));
        assertThat(browser.cookie(TestBrowser.CSRF_COOKIE)).isNotEqualTo(tokenBeforeLogin);
        assertThat(browser.postWithCsrf("/logout", null).statusCode()).isEqualTo(200);
    }

    @Test
    void theFirstResponseAfterLogoutCarriesAFreshCsrfToken() {
        TestBrowser browser = signedInAs(Identity.customer());
        browser.get("/api/me");
        String tokenBeforeLogout = browser.cookie(TestBrowser.CSRF_COOKIE);

        browser.postWithCsrf("/logout", null);
        HttpResponse<String> firstAfterLogout = browser.get("/api/me");

        assertThat(setCookies(firstAfterLogout)).anyMatch(header -> header.startsWith("__Host-XSRF-TOKEN="));
        assertThat(browser.cookie(TestBrowser.CSRF_COOKIE)).isNotNull().isNotEqualTo(tokenBeforeLogout);
    }

    private static java.util.List<String> setCookies(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie");
    }
}
