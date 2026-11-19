package dev.shop.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StockManagementTest {

    private final StockRepository repository = mock(StockRepository.class);
    private final StockManagement stock = new StockManagement(repository);

    @Test
    void aProductWithoutAStockRowIsABugNotAMissingResource() {
        when(repository.find(7)).thenReturn(Optional.empty());
        when(repository.findAll(List.of(1L, 7L))).thenReturn(Map.of(1L, new StockLevel(1, 0, 0)));

        assertThatThrownBy(() -> stock.levelOf(7))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("7");
        assertThatThrownBy(() -> stock.levelsOf(List.of(1L, 7L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("7");
    }

    @Test
    void returnsALevelForEveryRequestedProduct() {
        StockLevel level = new StockLevel(3, 1, 2);
        when(repository.findAll(List.of(5L))).thenReturn(Map.of(5L, level));

        assertThat(stock.levelsOf(List.of(5L))).containsEntry(5L, level);
    }

    @Test
    void anEmptyRequestNeedsNoQuery() {
        assertThat(stock.levelsOf(List.of())).isEmpty();

        verifyNoInteractions(repository);
    }
}
