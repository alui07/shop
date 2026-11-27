package dev.shop.api.order;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Hold time and anti-hoarding limits (D22), and the batch size of the expiry job. */
@Validated
@ConfigurationProperties("shop.checkout")
record CheckoutProperties(
        @NotNull @DefaultValue("15m") Duration reservationTtl,
        @Min(1) @DefaultValue("3") int maxPendingOrders,
        @Min(1) @DefaultValue("10") int maxReservedPerProduct,
        @Min(1) @DefaultValue("50") int expiryBatchSize,
        @NotNull @DefaultValue("30m") Duration latePaymentWindow) {}
