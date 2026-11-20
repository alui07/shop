package dev.shop.api.order;

import java.util.List;

record OrderPage(List<OrderSummary> items, int page, int size, long totalItems, int totalPages) {}
