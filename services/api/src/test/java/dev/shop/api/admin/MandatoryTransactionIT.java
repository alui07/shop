package dev.shop.api.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shop.api.catalog.NewProduct;
import dev.shop.api.catalog.ProductAdministration;
import dev.shop.api.catalog.ProductChanges;
import dev.shop.api.inventory.StockManagement;
import dev.shop.api.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.IllegalTransactionStateException;

/** The write APIs only run inside the admin service's audited transaction. */
@IntegrationTest
@WithMockUser(roles = "ADMIN")
class MandatoryTransactionIT {

    @Autowired
    private ProductAdministration products;

    @Autowired
    private StockManagement stock;

    @Test
    void writesWithoutATransactionAreRefused() {
        assertThatThrownBy(() -> products.create(new NewProduct("s", "N", "D", 1, "c", true)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> products.update(1, 0, new ProductChanges("N", "D", 1, "c", true)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> stock.setOnHand(1, 0, 1)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> stock.createFor(1)).isInstanceOf(IllegalTransactionStateException.class);
    }
}
