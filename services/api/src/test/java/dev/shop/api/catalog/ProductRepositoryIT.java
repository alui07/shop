package dev.shop.api.catalog;

import static dev.shop.api.support.CatalogFixtures.ACTIVE_PRODUCTS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.catalog.pricing.Money;
import dev.shop.api.support.CatalogFixtures;
import dev.shop.api.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class ProductRepositoryIT {

    @Autowired
    private ProductRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void loadCatalog() {
        CatalogFixtures.loadStandardCatalog(jdbc);
    }

    @Test
    void listsCategoriesByName() {
        assertThat(repository.findCategories())
                .extracting(Category::name)
                .containsExactly("Garden", "Kitchen", "Office");
    }

    @Test
    void countsOnlyActiveProducts() {
        assertThat(repository.count(search(null, null, ProductSort.NAME, 0, 12)))
                .isEqualTo(ACTIVE_PRODUCTS);
    }

    @Test
    void sortsByNameWithSlugAsTiebreak() {
        List<String> expected = jdbc.sql("SELECT slug FROM product WHERE active ORDER BY name, slug")
                .query(String.class)
                .list();

        assertThat(slugs(search(null, null, ProductSort.NAME, 0, ACTIVE_PRODUCTS)))
                .containsExactlyElementsOf(expected);
        assertThat(slugs(search("mug", null, ProductSort.NAME, 0, 12))).containsExactly("twin-mug-a", "twin-mug-b");
    }

    @Test
    void sortsByPriceAscendingBreakingTiesByName() {
        List<ProductSummary> page = repository.findPage(search("lamp", null, ProductSort.PRICE_ASC, 0, 12));

        assertThat(page).extracting(ProductSummary::slug).containsExactly("reading-light", "brass-lamp", "table-lamp");
        assertThat(page).extracting(product -> product.price().amountMinor()).containsExactly(900L, 1000L, 1000L);
    }

    @Test
    void sortsByPriceDescendingBreakingTiesByName() {
        assertThat(slugs(search("lamp", null, ProductSort.PRICE_DESC, 0, 12)))
                .containsExactly("brass-lamp", "table-lamp", "reading-light");
    }

    @Test
    void ranksNameMatchesAboveDescriptionMatches() {
        assertThat(slugs(search("lamp", null, ProductSort.RELEVANCE, 0, 12)))
                .containsExactly("brass-lamp", "table-lamp", "reading-light");
    }

    @Test
    void supportsWebSearchSyntax() {
        assertThat(slugs(search("lamp -table", null, ProductSort.NAME, 0, 12)))
                .containsExactly("brass-lamp", "reading-light");
    }

    @Test
    void treatsSearchTextAsDataNotSql() {
        ProductSearch hostile = search("'; DROP TABLE product; --", null, ProductSort.NAME, 0, 12);

        assertThat(repository.count(hostile)).isZero();
        assertThat(repository.count(search(null, null, ProductSort.NAME, 0, 12)))
                .isEqualTo(ACTIVE_PRODUCTS);
    }

    @Test
    void filtersByCategoryAndCombinesItWithSearch() {
        assertThat(repository.count(search(null, "garden", ProductSort.NAME, 0, 12)))
                .isEqualTo(25);
        assertThat(slugs(search("lamp", "office", ProductSort.NAME, 0, 12))).hasSize(3);
        assertThat(repository.count(search("lamp", "garden", ProductSort.NAME, 0, 12)))
                .isZero();
    }

    @Test
    void unknownCategoryIsAnEmptyResult() {
        ProductSearch unknown = search(null, "no-such-category", ProductSort.NAME, 0, 12);

        assertThat(repository.count(unknown)).isZero();
        assertThat(repository.findPage(unknown)).isEmpty();
    }

    @Test
    void pagesAcrossBoundaries() {
        assertThat(repository.findPage(search(null, null, ProductSort.NAME, 0, 12)))
                .hasSize(12);
        assertThat(repository.findPage(search(null, null, ProductSort.NAME, 1, 12)))
                .hasSize(12);
        assertThat(repository.findPage(search(null, null, ProductSort.NAME, 2, 12)))
                .hasSize(6);
        assertThat(repository.findPage(search(null, null, ProductSort.NAME, 3, 12)))
                .isEmpty();
    }

    @Test
    void findsAnActiveProductBySlug() {
        ProductDetail detail = repository.findBySlug("brass-lamp").orElseThrow();

        assertThat(detail)
                .isEqualTo(new ProductDetail(
                        "brass-lamp",
                        "Brass Lamp",
                        "A tidy desk accessory",
                        new Money(1000, "EUR"),
                        new Category("office", "Office")));
    }

    @Test
    void hidesInactiveAndUnknownProducts() {
        assertThat(repository.findBySlug("hidden-lamp")).isEmpty();
        assertThat(repository.findBySlug("nothing-here")).isEmpty();
    }

    private List<String> slugs(ProductSearch search) {
        return repository.findPage(search).stream().map(ProductSummary::slug).toList();
    }

    private static ProductSearch search(String text, String category, ProductSort sort, int page, int size) {
        return new ProductSearch(text, category, sort, page, size);
    }
}
