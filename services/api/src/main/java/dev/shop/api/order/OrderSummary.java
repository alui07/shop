package dev.shop.api.order;

import dev.shop.api.catalog.pricing.Money;
import java.time.Instant;
import java.util.UUID;

record OrderSummary(UUID id, OrderStatus status, Instant createdAt, Instant expiresAt, int itemCount, Money total) {}
