package dev.shop.api.catalog;

record ProductSearch(String text, String categorySlug, ProductSort sort, int page, int size) {

    boolean hasText() {
        return text != null;
    }

    boolean hasCategory() {
        return categorySlug != null;
    }

    long offset() {
        return (long) page * size;
    }
}
