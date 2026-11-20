package dev.shop.api.cart;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

record CartLineInput(
        @NotNull @Min(CartRules.MIN_QUANTITY) @Max(CartRules.MAX_QUANTITY)
        Integer quantity) {}
