package dev.shop.api.admin;

import dev.shop.api.catalog.Category;
import dev.shop.api.catalog.ManagedProduct;
import dev.shop.api.catalog.pricing.Money;
import dev.shop.api.inventory.StockLevel;

record AdminProduct(
        long id,
        String slug,
        String name,
        String description,
        Money price,
        Category category,
        boolean active,
        long version,
        AdminStock stock) {

    static AdminProduct of(ManagedProduct product, StockLevel stock) {
        return new AdminProduct(
                product.id(),
                product.slug(),
                product.name(),
                product.description(),
                product.price(),
                product.category(),
                product.active(),
                product.version(),
                AdminStock.of(stock));
    }
}
