package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

class ShopAuthPropertiesTest {

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
