package dev.shop.api.order;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

record CheckoutInput(
        @NotNull @Min(0) Long cartVersion,
        @NotNull @Min(0) @Max(CheckoutInput.MAX_TOTAL_MINOR) Long expectedTotalMinor) {

    static final long MAX_TOTAL_MINOR = 1_000_000_000_000L;
}
