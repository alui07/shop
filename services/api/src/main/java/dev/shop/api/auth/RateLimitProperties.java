package dev.shop.api.auth;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Per-account request limits. {@code scale} multiplies every rule's capacity; only the load-test overlay raises it.
 */
@Validated
@ConfigurationProperties("shop.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @Min(1) @DefaultValue("1") int scale) {}
