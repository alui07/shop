package dev.shop.api.catalog.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.ShopIT;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ProductPricingIT extends ShopIT {

    @Autowired
    private ProductPricing pricing;

    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void findsProductsByIdIncludingInactiveOnesInAscendingOrder() {
        List<PricedProduct> found = pricing.findByIds(List.of(retiredPenId, lampId));

        assertThat(found).extracting(PricedProduct::slug).containsExactly(LAMP, RETIRED_PEN);
        assertThat(found.get(0).price()).isEqualTo(new Money(LAMP_PRICE, "EUR"));
        assertThat(found.get(0).active()).isTrue();
        assertThat(found.get(1).active()).isFalse();
    }

    @Test
    void noIdsMeansNoProducts() {
        assertThat(pricing.findByIds(List.of())).isEmpty();
    }

    @Test
    void findsOneProductBySlugIncludingInactiveOnes() {
        assertThat(pricing.findBySlug(NOTEBOOK))
                .get()
                .extracting(PricedProduct::name)
                .isEqualTo("Notebook");
        assertThat(pricing.findBySlug(RETIRED_PEN))
                .get()
                .extracting(PricedProduct::active)
                .isEqualTo(false);
        assertThat(pricing.findBySlug("nothing-here")).isEmpty();
    }

    @Test
    void lockForCheckoutReturnsTheRowsInAscendingIdOrder() {
        List<PricedProduct> locked = new TransactionTemplate(transactions)
                .execute(status -> pricing.lockForCheckout(List.of(notebookId, lampId)));

        assertThat(locked).extracting(PricedProduct::id).containsExactly(lampId, notebookId);
    }
}
