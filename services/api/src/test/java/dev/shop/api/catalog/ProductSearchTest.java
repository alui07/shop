package dev.shop.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProductSearchTest {

    @Test
    void reportsWhichFiltersArePresent() {
        assertThat(new ProductSearch("lamp", null, ProductSort.NAME, 0, 12).hasText())
                .isTrue();
        assertThat(new ProductSearch(null, "garden", ProductSort.NAME, 0, 12).hasText())
                .isFalse();
        assertThat(new ProductSearch(null, "garden", ProductSort.NAME, 0, 12).hasCategory())
                .isTrue();
        assertThat(new ProductSearch("lamp", null, ProductSort.NAME, 0, 12).hasCategory())
                .isFalse();
    }

    @Test
    void computesTheOffsetWithoutOverflowingAnInt() {
        assertThat(new ProductSearch(null, null, ProductSort.NAME, 3, 12).offset())
                .isEqualTo(36);
        assertThat(new ProductSearch(null, null, ProductSort.NAME, Integer.MAX_VALUE, 48).offset())
                .isEqualTo(Integer.MAX_VALUE * 48L);
    }
}
