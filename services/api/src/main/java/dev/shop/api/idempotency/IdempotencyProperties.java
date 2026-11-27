package dev.shop.api.idempotency;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** How long a key is remembered and how many expired keys one cleanup statement deletes. */
@Validated
@ConfigurationProperties("shop.idempotency")
record IdempotencyProperties(
        @NotNull @DefaultValue("24h") Duration ttl,
        @Min(1) @DefaultValue("1000") int maintenanceBatchSize) {}
