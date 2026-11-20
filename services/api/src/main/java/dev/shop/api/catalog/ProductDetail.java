package dev.shop.api.catalog;

import dev.shop.api.catalog.pricing.Money;

record ProductDetail(String slug, String name, String description, Money price, Category category) {}
