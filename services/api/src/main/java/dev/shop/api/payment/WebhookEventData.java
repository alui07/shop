package dev.shop.api.payment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;

record WebhookEventData(
        @NotNull
        @Size(min = 1, max = WebhookEvent.MAX_ID_LENGTH)
        @Schema(description = "The provider's id for the payment")
        String providerPaymentId,

        @NotNull @Schema(description = "The payment id the shop gave the provider when creating it")
        UUID merchantReference,

        @PositiveOrZero long amountMinor,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {}
