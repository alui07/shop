package dev.shop.api.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

record StockInput(
        @NotNull @Min(0) @Max(Rules.MAX_ON_HAND) Integer onHand,
        @NotNull @Min(0) Long version) {}
