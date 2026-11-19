package dev.shop.api.admin;

import java.util.List;

record AdminProductPage(List<AdminProduct> items, int page, int size, long totalItems, int totalPages) {}
