package dev.shop.api.catalog;

/** A product as the administration sees it: inactive ones included, with the version used for optimistic locking. */
public record ManagedProduct(
        long id,
        String slug,
        String name,
        String description,
        Money price,
        Category category,
        boolean active,
        long version) {}
