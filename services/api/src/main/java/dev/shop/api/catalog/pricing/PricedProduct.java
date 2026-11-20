package dev.shop.api.catalog.pricing;

public record PricedProduct(long id, String slug, String name, Money price, boolean active) {}
