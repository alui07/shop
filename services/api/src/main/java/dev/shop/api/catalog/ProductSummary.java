package dev.shop.api.catalog;

record ProductSummary(String slug, String name, Money price, Category category) {}
