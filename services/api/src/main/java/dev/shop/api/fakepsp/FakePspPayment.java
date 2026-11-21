package dev.shop.api.fakepsp;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A payment on the fake provider's page")
record FakePspPayment(
        long amountMinor,
        String currency,
        UUID merchantReference,
        FakePspStatus status,

        @Schema(description = "Relative URL of the order to return to, /orders/{id}")
        String returnUrl,

        @Schema(description = "After this the page can no longer be completed")
        Instant expiresAt) {}
