package dev.shop.api.cart;

import dev.shop.api.catalog.pricing.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "A customer's cart. An absent cart is an empty one with version 0.")
record Cart(long version, List<CartLine> lines, int itemCount, Money total) {}
