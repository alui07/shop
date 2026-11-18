package dev.shop.api.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Where the browser and the API each reach Keycloak, and the session lifetimes. Secrets are checked with
 * {@code @NotBlank} only, so a failed binding never echoes a value.
 */
@Validated
@ConfigurationProperties("shop.auth")
public record ShopAuthProperties(
        @NotBlank String publicOrigin,
        @NotBlank String issuer,
        @NotBlank String internalRealmUrl,
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @NotNull @DefaultValue("8h") Duration absoluteSessionTimeout,
        @NotNull Duration idleSessionTimeout,
        @NotNull @DefaultValue("5m") Duration loginSessionTimeout,
        @NotNull @DefaultValue("5m") Duration revalidateAfter,
        @NotNull @DefaultValue("15m") Duration revalidateGrace) {

    @Override
    public String toString() {
        return "ShopAuthProperties[publicOrigin=" + publicOrigin + ", issuer=" + issuer + ", internalRealmUrl="
                + internalRealmUrl + ", clientId=" + clientId + ", clientSecret=<redacted>]";
    }

    String publicOriginWithoutTrailingSlash() {
        return withoutTrailingSlash(publicOrigin);
    }

    String publicHome() {
        return publicOriginWithoutTrailingSlash() + "/";
    }

    static String withoutTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
