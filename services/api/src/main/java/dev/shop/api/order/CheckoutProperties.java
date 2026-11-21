package dev.shop.api.order;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Hold time and anti-hoarding limits (D22), idempotency lifetime and the batch sizes of the background jobs. */
@Validated
@ConfigurationProperties("shop.checkout")
record CheckoutProperties(
        @NotNull @DefaultValue("15m") Duration reservationTtl,
        @Min(1) @DefaultValue("3") int maxPendingOrders,
        @Min(1) @DefaultValue("10") int maxReservedPerProduct,
        @NotNull @DefaultValue("24h") Duration idempotencyTtl,
        @Min(1) @DefaultValue("50") int expiryBatchSize,
        @Min(1) @DefaultValue("1000") int maintenanceBatchSize,
        @NotNull @DefaultValue("30m") Duration latePaymentWindow) {}
