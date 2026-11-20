package dev.shop.api.catalog;

import dev.shop.api.catalog.pricing.Money;

record ProductSummary(String slug, String name, Money price, Category category) {}
