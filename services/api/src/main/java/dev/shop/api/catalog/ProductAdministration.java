package dev.shop.api.catalog;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the admin module may do with the catalog: read every product, including inactive ones, and change them. A
 * change takes the product's row lock, so concurrent edits queue and the loser sees the new version.
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class ProductAdministration {

    private final ProductRepository repository;
    private final CatalogCacheInvalidation cacheInvalidation;

    ProductAdministration(ProductRepository repository, CatalogCacheInvalidation cacheInvalidation) {
        this.repository = repository;
        this.cacheInvalidation = cacheInvalidation;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ManagedProductPage list(String text, int page, int size) {
        String filter = text == null || text.isBlank() ? null : text.strip();
        long totalItems = repository.countAll(filter);
        List<ManagedProduct> items = repository.findAllPage(filter, page, size);
        int totalPages = (int) ((totalItems + size - 1) / size);
        return new ManagedProductPage(items, page, size, totalItems, totalPages);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ManagedProduct get(long id) {
        return repository.findManagedById(id).orElseThrow(ProductNotFoundException::new);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ManagedProduct create(NewProduct product) {
        long categoryId = categoryId(product.categorySlug());
        long id = repository.insertIfSlugFree(product, categoryId).orElseThrow(DuplicateSlugException::new);
        cacheInvalidation.afterCommit();
        return get(id);
    }

    /** Checks the version, then writes; a request that changes nothing keeps the version and returns the row as is. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ProductUpdate update(long id, long expectedVersion, ProductChanges changes) {
        ManagedProduct before = repository.lockManagedById(id).orElseThrow(ProductNotFoundException::new);
        if (before.version() != expectedVersion) {
            throw new VersionConflictException();
        }
        long categoryId = categoryId(changes.categorySlug());
        if (isUnchanged(before, changes)) {
            return new ProductUpdate(before, before);
        }
        repository.update(id, changes, categoryId);
        cacheInvalidation.afterCommit();
        return new ProductUpdate(before, get(id));
    }

    private long categoryId(String categorySlug) {
        return repository.findCategoryId(categorySlug).orElseThrow(UnknownCategoryException::new);
    }

    private static boolean isUnchanged(ManagedProduct current, ProductChanges changes) {
        return current.name().equals(changes.name())
                && current.description().equals(changes.description())
                && current.price().amountMinor() == changes.priceMinor()
                && current.category().slug().equals(changes.categorySlug())
                && current.active() == changes.active();
    }
}
