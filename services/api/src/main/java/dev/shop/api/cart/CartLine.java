package dev.shop.api.cart;

import dev.shop.api.catalog.pricing.Money;
import io.swagger.v3.oas.annotations.media.Schema;

record CartLine(
        String productSlug,
        String name,
        Money unitPrice,
        int quantity,
        Money lineTotal,

        @Schema(description = "False when the product is inactive or the quantity is more than is in stock")
        boolean available) {}
