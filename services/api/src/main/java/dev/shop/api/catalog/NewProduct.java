package dev.shop.api.catalog;

public record NewProduct(
        String slug, String name, String description, long priceMinor, String categorySlug, boolean active) {}
