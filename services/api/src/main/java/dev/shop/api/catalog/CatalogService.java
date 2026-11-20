package dev.shop.api.catalog;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class CatalogService {

    private final ProductRepository repository;

    CatalogService(ProductRepository repository) {
        this.repository = repository;
    }

    List<Category> categories() {
        return repository.findCategories();
    }

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

    ProductDetail product(String slug) {
        return repository.findBySlug(slug).orElseThrow(() -> new ProductNotFoundException());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
