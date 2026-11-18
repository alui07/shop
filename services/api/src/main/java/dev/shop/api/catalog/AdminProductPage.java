package dev.shop.api.catalog;

import java.util.List;

public record AdminProductPage(List<AdminProduct> items, int page, int size, long totalItems, int totalPages) {}
