package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AuthIT;
import dev.shop.api.support.TestBrowser;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@ActiveProfiles("loadtest")
@TestPropertySource(
        properties = {
            "shop.auth.public-origin=http://127.0.0.1",
            "shop.loadtest.token=" + LoadtestRateLimitIT.TOKEN,
            "shop.loadtest.max-mints-per-second=2"
        })
class LoadtestRateLimitIT extends AuthIT {

    static final String TOKEN = "limit-token-limit-token-limit-token-limit";

    @Test
    void answers429WithRetryAfterWhenTheWindowIsFullAndRecoversInTheNextSecond() {
        HttpResponse<String> limited = null;
        for (int attempt = 0; attempt < 20 && limited == null; attempt++) {
            HttpResponse<String> response =
                    browser().post("/api/loadtest/sessions", null, "Shop-Loadtest-Token", TOKEN);
            limited = response.statusCode() == 429 ? response : null;
        }

        assertThat(limited).isNotNull();
        assertThat(limited.headers().firstValue("Retry-After")).contains("1");
        clock.advance(Duration.ofSeconds(2));
        TestBrowser recovered = browser();
        assertThat(recovered
                        .post("/api/loadtest/sessions", null, "Shop-Loadtest-Token", TOKEN)
                        .statusCode())
                .isEqualTo(201);
    }
}
