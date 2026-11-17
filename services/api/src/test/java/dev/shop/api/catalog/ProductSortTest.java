package dev.shop.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProductSortTest {

    @ParameterizedTest
    @CsvSource({"relevance,RELEVANCE", "name,NAME", "price_asc,PRICE_ASC", "price_desc,PRICE_DESC"})
    void mapsWireNamesToConstantsAndBack(String wireName, ProductSort expected) {
        assertThat(ProductSort.fromWireName(wireName)).isEqualTo(expected);
        assertThat(expected.wireName()).isEqualTo(wireName);
    }

    @Test
    void rejectsUnknownWireNames() {
        assertThatIllegalArgumentException().isThrownBy(() -> ProductSort.fromWireName("cheapest"));
        assertThatIllegalArgumentException().isThrownBy(() -> ProductSort.fromWireName("NAME"));
    }

    @Test
    void everySortEndsWithTheSlugTiebreak() {
        for (ProductSort sort : ProductSort.values()) {
            assertThat(sort.orderBy()).endsWith("p.slug");
        }
    }

    @Test
    void defaultsToRelevanceWhenSearchingAndNameOtherwise() {
        assertThat(ProductSort.resolve(null, true)).isEqualTo(ProductSort.RELEVANCE);
        assertThat(ProductSort.resolve(null, false)).isEqualTo(ProductSort.NAME);
    }

    @Test
    void relevanceWithoutSearchTextBehavesAsName() {
        assertThat(ProductSort.resolve(ProductSort.RELEVANCE, false)).isEqualTo(ProductSort.NAME);
        assertThat(ProductSort.resolve(ProductSort.RELEVANCE, true)).isEqualTo(ProductSort.RELEVANCE);
    }

    @Test
    void keepsAnExplicitSortWithOrWithoutSearchText() {
        assertThat(ProductSort.resolve(ProductSort.PRICE_ASC, true)).isEqualTo(ProductSort.PRICE_ASC);
        assertThat(ProductSort.resolve(ProductSort.PRICE_DESC, false)).isEqualTo(ProductSort.PRICE_DESC);
    }

    @Test
    void editorUsesWireNames() {
        ProductSortEditor editor = new ProductSortEditor();

        editor.setAsText("price_desc");

        assertThat(editor.getValue()).isEqualTo(ProductSort.PRICE_DESC);
        assertThatIllegalArgumentException().isThrownBy(() -> editor.setAsText("PRICE_DESC"));
    }
}
