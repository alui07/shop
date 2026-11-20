package dev.shop.api.admin;

import dev.shop.api.catalog.ManagedProduct;
import dev.shop.api.catalog.ManagedProductPage;
import dev.shop.api.catalog.NewProduct;
import dev.shop.api.catalog.ProductAdministration;
import dev.shop.api.catalog.ProductChanges;
import dev.shop.api.catalog.ProductUpdate;
import dev.shop.api.customer.CurrentUser;
import dev.shop.api.inventory.StockLevel;
import dev.shop.api.inventory.StockManagement;
import dev.shop.api.inventory.StockUpdate;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One transaction per change: the catalog or stock write and its audit row commit together or not at all. Writes run
 * at READ COMMITTED on purpose: they wait on the row lock and then see the new version, which is a 409, where a
 * stricter level would fail the waiting request with a serialisation error.
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
class AdminProductService {

    private final ProductAdministration products;
    private final StockManagement stock;
    private final AuditLog audit;

    AdminProductService(ProductAdministration products, StockManagement stock, AuditLog audit) {
        this.products = products;
        this.stock = stock;
        this.audit = audit;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminProductPage list(String text, int page, int size) {
        ManagedProductPage managed = products.list(text, page, size);
        Map<Long, StockLevel> levels =
                stock.levelsOf(managed.items().stream().map(ManagedProduct::id).toList());
        List<AdminProduct> items = managed.items().stream()
                .map(product -> AdminProduct.of(product, levels.get(product.id())))
                .toList();
        return new AdminProductPage(items, managed.page(), managed.size(), managed.totalItems(), managed.totalPages());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminProduct get(long id) {
        ManagedProduct product = products.get(id);
        return AdminProduct.of(product, stock.levelOf(id));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminProduct create(CurrentUser actor, ProductInput input) {
        ManagedProduct created = products.create(new NewProduct(
                input.slug(),
                input.name(),
                input.description(),
                input.priceMinor(),
                input.categorySlug(),
                input.active()));
        StockLevel level = stock.createFor(created.id());
        audit.record(actor, "product.created", "product", created.id(), AuditChanges.productCreated(created));
        return AdminProduct.of(created, level);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminProduct update(CurrentUser actor, long id, ProductUpdateInput input) {
        ProductUpdate update = products.update(
                id,
                input.version(),
                new ProductChanges(
                        input.name(), input.description(), input.priceMinor(), input.categorySlug(), input.active()));
        Map<String, Map<String, Object>> changes = AuditChanges.productUpdated(update.before(), update.after());
        if (!changes.isEmpty()) {
            audit.record(actor, "product.updated", "product", id, changes);
        }
        return AdminProduct.of(update.after(), stock.levelOf(id));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminProduct setStock(CurrentUser actor, long id, StockInput input) {
        StockUpdate update = stock.setOnHand(id, input.version(), input.onHand());
        if (!update.before().equals(update.after())) {
            audit.record(actor, "stock.set", "stock", id, AuditChanges.stockSet(update.before(), update.after()));
        }
        return AdminProduct.of(products.get(id), update.after());
    }
}
