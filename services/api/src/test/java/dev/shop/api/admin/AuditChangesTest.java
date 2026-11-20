package dev.shop.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.catalog.Category;
import dev.shop.api.catalog.ManagedProduct;
import dev.shop.api.catalog.pricing.Money;
import dev.shop.api.inventory.StockLevel;
import org.junit.jupiter.api.Test;

class AuditChangesTest {

    private static ManagedProduct product(String name, long price, String category, boolean active) {
        return new ManagedProduct(
                1, "slug", name, "text", new Money(price, "EUR"), new Category(category, "C"), active, 0);
    }

    @Test
    void anUpdateRecordsOnlyTheFieldsThatDiffer() {
        var changes = AuditChanges.productUpdated(product("A", 100, "x", true), product("B", 100, "y", true));

        assertThat(changes).containsOnlyKeys("categorySlug", "name");
        assertThat(changes.get("name")).containsEntry("from", "A").containsEntry("to", "B");
    }

    @Test
    void identicalProductsHaveNoChanges() {
        assertThat(AuditChanges.productUpdated(product("A", 100, "x", true), product("A", 100, "x", true)))
                .isEmpty();
    }

    @Test
    void aCreateRecordsEveryFieldFromNull() {
        var changes = AuditChanges.productCreated(product("A", 100, "x", false));

        assertThat(changes).containsOnlyKeys("slug", "name", "description", "priceMinor", "categorySlug", "active");
        assertThat(changes.get("active")).containsEntry("from", null).containsEntry("to", false);
    }

    @Test
    void aStockChangeAlsoRecordsTheUnchangedReservedQuantity() {
        var changes = AuditChanges.stockSet(new StockLevel(5, 2, 0), new StockLevel(9, 2, 1));

        assertThat(changes.get("onHand")).containsEntry("from", 5).containsEntry("to", 9);
        assertThat(changes.get("reserved")).containsEntry("from", 2).containsEntry("to", 2);
    }
}
