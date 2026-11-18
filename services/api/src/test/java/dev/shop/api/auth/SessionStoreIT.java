package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AuthIT;
import dev.shop.api.support.Identity;
import dev.shop.api.support.TestBrowser;
import java.io.File;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class SessionStoreIT extends AuthIT {

    @Test
    void aSessionHoldingATypeOutsideTheAllowlistIsDeletedAndTreatedAsSignedOut() {
        TestBrowser browser = browserWithSession(session -> session.setAttribute("smuggled", new File("/tmp/x")));
        String sessionId = sessionIdOf(browser);
        assertThat(redis.hasKey("spring:session:sessions:" + sessionId)).isTrue();

        HttpResponse<String> response = browser.get("/api/me");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"authenticated\":false");
        assertThat(redis.hasKey("spring:session:sessions:" + sessionId)).isFalse();
    }

    @Test
    void deletingASessionDropsItsBackChannelMapping() {
        Identity identity = Identity.customer();
        TestBrowser browser = signedInAs(identity);
        String sessionId = sessionIdOf(browser);
        assertThat(redis.hasKey("shop:oidc:session:" + sessionId)).isTrue();

        sessions.deleteById(sessionId);

        assertThat(redis.hasKey("shop:oidc:session:" + sessionId)).isFalse();
        assertThat(redis.opsForSet().members("shop:oidc:sid:" + identity.sessionId()))
                .isEmpty();
        assertThat(redis.opsForSet().members("shop:oidc:sub:" + identity.subject()))
                .doesNotContain(sessionId);
    }

    @Test
    void theRegistryKeysExpireWithTheAbsoluteTimeout() {
        Identity identity = Identity.customer();
        TestBrowser browser = signedInAs(identity);

        Long seconds = redis.getExpire("shop:oidc:session:" + sessionIdOf(browser));

        assertThat(seconds).isBetween(8 * 3600L - 10, 8 * 3600L);
        assertThat(redis.getExpire("shop:oidc:sid:" + identity.sessionId())).isBetween(8 * 3600L - 10, 8 * 3600L);
    }
}
