package dev.shop.api.inventory;

/** {@code version} changes whenever {@code onHand} does, never for {@code reserved} alone. */
public record StockLevel(int onHand, int reserved, long version) {}
