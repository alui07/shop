package dev.shop.api.catalog;

import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class CatalogService {

    private final ProductRepository repository;

    CatalogService(ProductRepository repository) {
        this.repository = repository;
    }

    @Cacheable(CatalogCaches.CATEGORIES)
    List<Category> categories() {
        return repository.findCategories();
    }

    /** Free-text searches are not cached: their key space is unbounded and their hit rate low. */
    @Cacheable(
            cacheNames = CatalogCaches.PRODUCT_PAGE,
            condition = "#text == null || #text.isBlank()",
            key = "new org.springframework.cache.interceptor.SimpleKey(#categorySlug, #requestedSort, #page, #size)")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    ProductPage search(String text, String categorySlug, ProductSort requestedSort, int page, int size) {
        String normalisedText = blankToNull(text);
        ProductSort sort = ProductSort.resolve(requestedSort, normalisedText != null);
        ProductSearch search = new ProductSearch(normalisedText, blankToNull(categorySlug), sort, page, size);
        long totalItems = repository.count(search);
        List<ProductSummary> items = repository.findPage(search);
        int totalPages = (int) ((totalItems + size - 1) / size);
        return new ProductPage(items, page, size, totalItems, totalPages);
    }

    @Cacheable(CatalogCaches.PRODUCT)
    ProductDetail product(String slug) {
        return repository.findBySlug(slug).orElseThrow(() -> new ProductNotFoundException());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
