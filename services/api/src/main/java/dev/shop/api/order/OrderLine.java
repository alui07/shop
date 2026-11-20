package dev.shop.api.order;

import dev.shop.api.catalog.pricing.Money;

record OrderLine(String productSlug, String name, Money unitPrice, int quantity, Money lineTotal) {}
