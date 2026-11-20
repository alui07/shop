package dev.shop.api.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shop.api.cart.CartCheckout;
import dev.shop.api.catalog.NewProduct;
import dev.shop.api.catalog.ProductAdministration;
import dev.shop.api.catalog.ProductChanges;
import dev.shop.api.catalog.pricing.ProductPricing;
import dev.shop.api.inventory.StockManagement;
import dev.shop.api.inventory.reservations.ReservationLine;
import dev.shop.api.inventory.reservations.StockReservations;
import dev.shop.api.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.IllegalTransactionStateException;

/** The write APIs only run inside their caller's transaction: the admin service's audited one, or checkout's. */
@IntegrationTest
@WithMockUser(roles = {"ADMIN", "CUSTOMER"})
class MandatoryTransactionIT {

    @Autowired
    private ProductAdministration products;

    @Autowired
    private StockManagement stock;

    @Autowired
    private StockReservations reservations;

    @Autowired
    private ProductPricing pricing;

    @Autowired
    private CartCheckout carts;

    @Test
    void writesWithoutATransactionAreRefused() {
        assertThatThrownBy(() -> products.create(new NewProduct("s", "N", "D", 1, "c", true)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> products.update(1, 0, new ProductChanges("N", "D", 1, "c", true)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> stock.setOnHand(1, 0, 1)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> stock.createFor(1)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void reservationsPricingLocksAndCartLocksNeedTheCallersTransaction() {
        UUID order = UUID.randomUUID();
        assertThatThrownBy(() -> reservations.reserve(order, List.of(new ReservationLine(1, 1))))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> reservations.release(order)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> reservations.releaseAll(List.of(order)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> pricing.lockForCheckout(List.of(1L)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> carts.lockForCheckout("s")).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> carts.clear(1)).isInstanceOf(IllegalTransactionStateException.class);
    }
}
