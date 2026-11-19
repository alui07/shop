package dev.shop.api.catalog;

/** Everything about a product an administrator may edit; the slug is fixed at creation. */
public record ProductChanges(String name, String description, long priceMinor, String categorySlug, boolean active) {}
