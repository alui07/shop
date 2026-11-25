package dev.shop.api.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** The credential and limits of the session-minting endpoint. The token is validated by length only and never echoed. */
@Validated
@ConfigurationProperties("shop.loadtest")
public record LoadtestProperties(
        String token,
        @Min(1) @DefaultValue("10") int maxMintsPerSecond,
        @NotNull @DefaultValue("5m") Duration sessionTimeout) {

    static final int MINIMUM_TOKEN_LENGTH = 32;

    @AssertTrue(message = "shop.loadtest.token must be at least 32 characters, not counting surrounding whitespace")
    boolean isTokenLongEnough() {
        return token != null && !token.isBlank() && token.strip().length() >= MINIMUM_TOKEN_LENGTH;
    }

    @Override
    public String toString() {
        return "LoadtestProperties[token=<redacted>, maxMintsPerSecond=" + maxMintsPerSecond + ", sessionTimeout="
                + sessionTimeout + "]";
    }
}
