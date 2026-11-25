package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AuthIT;
import dev.shop.api.support.TestBrowser;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** The shared context has no loadtest profile: the endpoint does not exist and the main chain denies the request. */
class LoadtestAbsentIT extends AuthIT {

    @Autowired
    private ApplicationContext context;

    @Test
    void noMintingBeansExist() {
        assertThat(context.getBeanNamesForType(LoadtestSessionController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(LoadtestGuard.class)).isEmpty();
    }

    @Test
    void aMintRequestWithAValidCsrfCookieAndHeaderIsDeniedByDenyAllAndSetsNoSession() {
        TestBrowser browser = browser();
        browser.get("/api/me");

        HttpResponse<String> response = browser.postWithCsrf(
                "/api/loadtest/sessions", null, "Shop-Loadtest-Token", "any-token-any-token-any-token-any");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().allValues("Set-Cookie"))
                .noneMatch(header -> header.startsWith("__Host-SESSION="));
    }
}
