package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

class ShopAuthPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ShopAuthProperties.class)
    static class Binding {}

    private ApplicationContextRunner runnerWithOrigin(String origin) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(Binding.class)
                .withPropertyValues(
                        "shop.auth.public-origin=" + origin,
                        "shop.auth.issuer=https://idp/realms/shop",
                        "shop.auth.internal-realm-url=http://kc/realms/shop",
                        "shop.auth.client-id=shop-api",
                        "shop.auth.client-secret=super-secret-value",
                        "shop.auth.idle-session-timeout=30m");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8088", "https://shop.example.test", "http://shop:8080"})
    void anHttpOriginIsAccepted(String origin) {
        runnerWithOrigin(origin).run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                " ",
                "shop.example.test",
                "ftp://shop.example.test",
                "https://shop.example.test/",
                "https://shop.example.test/app",
                "https://shop.example.test?x=1",
                "https://",
                "/orders"
            })
    void anOriginThatIsBlankRelativeOrCarriesAPathFailsTheStartup(String origin) {
        runnerWithOrigin(origin).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("publicOrigin");
            assertThat(context.getStartupFailure().toString()).doesNotContain("super-secret-value");
        });
    }

    @Test
    void anUnsetOriginFailsTheStartupToo() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(Binding.class)
                .withPropertyValues("shop.auth.public-origin=")
                .run(context -> assertThat(context).hasFailed());
    }

    private static ShopAuthProperties properties(String origin, String issuer, String internal) {
        return new ShopAuthProperties(
                origin,
                issuer,
                internal,
                "shop-api",
                "super-secret-value",
                Duration.ofHours(8),
                Duration.ofMinutes(30),
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                Duration.ofMinutes(15));
    }

    @Test
    void toStringNeverShowsTheClientSecret() {
        String text = properties("https://shop.example.test", "https://idp/realms/shop", "http://kc/realms/shop")
                .toString();

        assertThat(text).doesNotContain("super-secret-value").contains("<redacted>", "shop-api");
    }

    @Test
    void urlsAreBuiltTheSameWithOrWithoutTrailingSlashes() {
        ClientRegistration plain = KeycloakClientRegistration.from(
                properties("https://shop.example.test", "https://idp/realms/shop", "http://kc/realms/shop"));
        ClientRegistration slashed = KeycloakClientRegistration.from(
                properties("https://shop.example.test/", "https://idp/realms/shop/", "http://kc/realms/shop/"));

        assertThat(slashed.getRedirectUri()).isEqualTo(plain.getRedirectUri());
        assertThat(slashed.getProviderDetails().getAuthorizationUri())
                .isEqualTo(plain.getProviderDetails().getAuthorizationUri())
                .isEqualTo("https://idp/realms/shop/protocol/openid-connect/auth");
        assertThat(slashed.getProviderDetails().getTokenUri())
                .isEqualTo("http://kc/realms/shop/protocol/openid-connect/token");
        assertThat(slashed.getProviderDetails().getJwkSetUri())
                .isEqualTo("http://kc/realms/shop/protocol/openid-connect/certs");
        assertThat(slashed.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(properties("https://shop.example.test/", "i", "k").publicHome())
                .isEqualTo("https://shop.example.test/");
    }
}
