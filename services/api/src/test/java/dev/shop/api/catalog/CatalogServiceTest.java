package dev.shop.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CatalogServiceTest {

    private static final ProductSummary SUMMARY =
            new ProductSummary("a", "A", new Money(100, "EUR"), new Category("c", "C"));

    private final ProductRepository repository = mock(ProductRepository.class);
    private final CatalogService service = new CatalogService(repository);

    @Test
    void returnsTheCategoriesFromTheRepository() {
        List<Category> categories = List.of(new Category("c", "C"));
        when(repository.findCategories()).thenReturn(categories);

        assertThat(service.categories()).isEqualTo(categories);
    }

    @Test
    void roundsTheNumberOfPagesUp() {
        when(repository.count(org.mockito.ArgumentMatchers.any())).thenReturn(25L);
        when(repository.findPage(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(SUMMARY));

        ProductPage page = service.search(null, null, null, 2, 12);

        assertThat(page).isEqualTo(new ProductPage(List.of(SUMMARY), 2, 12, 25, 3));
    }

    @Test
    void anEmptyCatalogHasZeroPages() {
        when(repository.count(org.mockito.ArgumentMatchers.any())).thenReturn(0L);

        ProductPage page = service.search(null, null, null, 0, 12);

        assertThat(page.items()).isEmpty();
        assertThat(page.totalPages()).isZero();
    }

    @Test
    void anExactMultipleOfTheSizeAddsNoExtraPage() {
        when(repository.count(org.mockito.ArgumentMatchers.any())).thenReturn(24L);

        assertThat(service.search(null, null, null, 0, 12).totalPages()).isEqualTo(2);
    }

    @Test
    void trimsTextAndTreatsBlankValuesAsAbsent() {
        service.search("  lamp  ", "   ", null, 0, 12);
        service.search("   ", "garden", null, 0, 12);

        ArgumentCaptor<ProductSearch> searches = ArgumentCaptor.forClass(ProductSearch.class);
        verify(repository, org.mockito.Mockito.times(2)).count(searches.capture());
        assertThat(searches.getAllValues())
                .containsExactly(
                        new ProductSearch("lamp", null, ProductSort.RELEVANCE, 0, 12),
                        new ProductSearch(null, "garden", ProductSort.NAME, 0, 12));
    }

    @Test
    void resolvesRelevanceWithoutTextToName() {
        service.search(null, null, ProductSort.RELEVANCE, 0, 12);

        ArgumentCaptor<ProductSearch> search = ArgumentCaptor.forClass(ProductSearch.class);
        verify(repository).count(search.capture());
        assertThat(search.getValue().sort()).isEqualTo(ProductSort.NAME);
    }

    @Test
    void returnsAProductBySlug() {
        ProductDetail detail = new ProductDetail("a", "A", "text", new Money(100, "EUR"), new Category("c", "C"));
        when(repository.findBySlug("a")).thenReturn(Optional.of(detail));

        assertThat(service.product("a")).isEqualTo(detail);
    }

    @Test
    void anUnknownProductIsNotFound() {
        when(repository.findBySlug("x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.product("x"))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining("Product not found");
    }
}
