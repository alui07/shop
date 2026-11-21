package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AuthIT;
import dev.shop.api.support.Identity;
import dev.shop.api.support.TestBrowser;
import java.net.http.HttpResponse;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

    static Stream<Arguments> customerUnsafeRoutes() {
        return Stream.of(
                Arguments.of("PUT", "/api/cart/lines/desk-lamp"),
                Arguments.of("DELETE", "/api/cart/lines/desk-lamp"),
                Arguments.of("POST", "/api/checkout"),
                Arguments.of("POST", "/api/orders/7f0f2a4e-5c1b-4f55-9d0a-2b0a4f3c9e11/cancel"),
                Arguments.of("POST", "/api/orders/7f0f2a4e-5c1b-4f55-9d0a-2b0a4f3c9e11/payment"),
                Arguments.of("POST", "/api/fake-psp/payments/unknown-token/complete"));
    }

    @ParameterizedTest(name = "{0} {1} needs the CSRF token")
    @MethodSource("customerUnsafeRoutes")
    void everyCustomerWriteRefusesARequestWithoutTheToken(String method, String path) {
        TestBrowser browser = signedInAs(Identity.customer());
        browser.get("/api/me");

        HttpResponse<String> response = browser.send(method, path, null);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("CSRF token missing or invalid");
    }

    @ParameterizedTest(name = "{0} {1} passes the CSRF check with the token")
    @MethodSource("customerUnsafeRoutes")
    void everyCustomerWriteGetsPastTheCsrfCheckWithTheToken(String method, String path) {
        TestBrowser browser = signedInAs(Identity.customer());
        browser.get("/api/me");

        HttpResponse<String> response =
                browser.send(method, path, null, "X-XSRF-TOKEN", browser.cookie(TestBrowser.CSRF_COOKIE));

        assertThat(response.statusCode()).isNotEqualTo(403);
    }

    private static java.util.List<String> setCookies(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie");
    }
}
