package dev.shop.api.order;

import dev.shop.api.catalog.pricing.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "A placed order. Prices are the ones at checkout.")
record Order(
        UUID id,
        OrderStatus status,
        Instant createdAt,

        @Schema(description = "When the stock held for an unpaid order is given back")
        Instant expiresAt,

        List<OrderLine> lines,
        Money total) {}
