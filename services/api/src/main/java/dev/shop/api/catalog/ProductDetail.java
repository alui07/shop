package dev.shop.api.catalog;

record ProductDetail(String slug, String name, String description, Money price, Category category) {}
