package dev.shop.api.catalog;

import java.util.List;

record ProductPage(List<ProductSummary> items, int page, int size, long totalItems, int totalPages) {}
