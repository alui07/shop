package dev.shop.api.catalog;

public record AdminProduct(
        long id, String slug, String name, String description, Money price, Category category, boolean active) {}
