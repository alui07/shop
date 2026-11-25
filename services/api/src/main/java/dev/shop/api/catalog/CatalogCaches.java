package dev.shop.api.catalog;

/** Names of the in-process catalog caches (declared in {@code spring.cache.cache-names}). */
final class CatalogCaches {

    static final String CATEGORIES = "categories";
    static final String PRODUCT = "product";
    static final String PRODUCT_PAGE = "productPage";

    private CatalogCaches() {}
}
