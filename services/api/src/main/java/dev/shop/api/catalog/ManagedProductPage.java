package dev.shop.api.catalog;

import java.util.List;

public record ManagedProductPage(List<ManagedProduct> items, int page, int size, long totalItems, int totalPages) {}
