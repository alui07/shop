package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class LoadtestGuardTest {

    private static final String LONG_TOKEN = "0123456789abcdef0123456789abcdef";

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({ShopAuthProperties.class, LoadtestProperties.class})
    static class Properties {
        @Bean
        LoadtestGuard guard(ShopAuthProperties auth) {
            return new LoadtestGuard(auth);
        }
    }

    private ApplicationContextRunner runner(String origin, String token) {
        return new ApplicationContextRunner()
                .withUserConfiguration(Properties.class)
                .withPropertyValues(
                        "shop.auth.public-origin=" + origin,
                        "shop.auth.issuer=https://idp.example.test/realms/shop",
                        "shop.auth.internal-realm-url=http://localhost:1/realms/shop",
                        "shop.auth.client-id=shop-api",
                        "shop.auth.client-secret=secret",
                        "shop.auth.idle-session-timeout=30m",
                        "shop.loadtest.token=" + token);
    }

    @Test
    void startsOnLocalhostAndLoopbackWithALongToken() {
        runner("http://localhost:5173", LONG_TOKEN)
                .run(context -> assertThat(context).hasNotFailed());
        runner("http://127.0.0.1", LONG_TOKEN)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void refusesToStartBehindARealHostname() {
        runner("https://shop.example.com", LONG_TOKEN)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseMessage(
                                "The loadtest profile requires shop.auth.public-origin on localhost or 127.0.0.1"));
    }

    @Test
    void refusesAShortTokenWithoutEchoingIt() {
        String shortToken = "short-secret-value";

        runner("http://localhost", shortToken).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("at least 32")
                    .satisfies(failure -> {
                        var trace = new java.io.StringWriter();
                        failure.printStackTrace(new java.io.PrintWriter(trace));
                        assertThat(trace.toString()).doesNotContain(shortToken);
                    });
        });
    }

    @Test
    void refusesABlankToken() {
        runner("http://localhost", "").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void refusesAWhitespaceOnlyOrPaddedShortTokenWithoutEchoingIt() {
        String padded = " ".repeat(20) + "short-secret" + " ".repeat(20);

        runner("http://localhost", " ".repeat(40))
                .run(context -> assertThat(context).hasFailed());
        runner("http://localhost", padded).run(context -> {
            assertThat(context).hasFailed();
            var trace = new java.io.StringWriter();
            context.getStartupFailure().printStackTrace(new java.io.PrintWriter(trace));
            assertThat(trace.toString()).doesNotContain("short-secret");
        });
    }

    @Test
    void aMissingTokenIsNotLongEnough() {
        assertThat(new LoadtestProperties(null, 10, java.time.Duration.ofMinutes(5)).isTokenLongEnough())
                .isFalse();
    }

    @Test
    void theToStringHidesTheToken() {
        assertThat(new LoadtestProperties(LONG_TOKEN, 10, java.time.Duration.ofMinutes(5)).toString())
                .doesNotContain(LONG_TOKEN);
    }

    @Test
    void anUnknownDigestAlgorithmIsRefused() {
        assertThatThrownBy(() -> LoadtestSessionController.digest("x", "NOPE"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
