package dev.shop.api.catalog;

import java.util.List;
import org.springframework.stereotype.Service;

/** What the admin module may do with the catalog: read every product, including inactive ones. */
@Service
public class ProductAdministration {

    private final ProductRepository repository;

    ProductAdministration(ProductRepository repository) {
        this.repository = repository;
    }

    public AdminProductPage list(String text, int page, int size) {
        String filter = text == null || text.isBlank() ? null : text.strip();
        long totalItems = repository.countAll(filter);
        List<AdminProduct> items = repository.findAllPage(filter, page, size);
        int totalPages = (int) ((totalItems + size - 1) / size);
        return new AdminProductPage(items, page, size, totalItems, totalPages);
    }
}
